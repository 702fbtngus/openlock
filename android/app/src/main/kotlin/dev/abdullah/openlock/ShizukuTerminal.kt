package dev.abdullah.openlock

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import moe.shizuku.manager.integration.EmbeddedShizuku
import moe.shizuku.server.IRemoteProcess
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One user-started shell command at a time, with bounded streamed output. */
class ShizukuTerminal(private val context: Context) : EventChannel.StreamHandler {
    private val main = Handler(Looper.getMainLooper())
    private val work = Executors.newSingleThreadExecutor()
    private val control = Executors.newSingleThreadExecutor()
    private val active = AtomicReference<Command?>()
    private var events: EventChannel? = null
    private var sink: EventChannel.EventSink? = null
    @Volatile private var disposed = false

    private class Command(val id: String) {
        val cancelled = AtomicBoolean(false)
        @Volatile var process: IRemoteProcess? = null
        var outputCharacters = 0
        var truncated = false
    }

    private class OutputPipe(val name: String, val descriptor: ParcelFileDescriptor) {
        val decoder = TerminalUtf8Decoder()
        val poll = StructPollfd().apply {
            fd = descriptor.fileDescriptor
            events = OsConstants.POLLIN.toShort()
        }
        var ended = false

        init {
            val flags = Os.fcntlInt(poll.fd, OsConstants.F_GETFL, 0)
            Os.fcntlInt(poll.fd, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
        }
    }

    fun register(messenger: BinaryMessenger) {
        events = EventChannel(messenger, "openlock/shizuku-terminal").also {
            it.setStreamHandler(this)
        }
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
        sink = events
    }

    override fun onCancel(arguments: Any?) {
        sink = null
        if (!disposed) active.get()?.let(::cancel)
    }

    fun status(result: MethodChannel.Result) {
        control.execute {
            val state = try {
                val service = EmbeddedShizuku.getService(context)
                if (service == null) mapOf("connected" to false) else
                    mapOf("connected" to true, "uid" to service.uid)
            } catch (_: Exception) {
                mapOf("connected" to false,
                    "message" to "Could not reach Shizuku. Open the manager and start the service.")
            }
            main.post { result.success(state) }
        }
    }

    fun start(id: String?, command: String?, result: MethodChannel.Result) {
        if (id.isNullOrBlank() || id.length > 128 || command.isNullOrBlank() ||
            command.length > 16384 || command.contains('\u0000')) {
            result.error("invalid_command", "Enter a shell command of up to 16,384 characters.", null)
            return
        }
        if (disposed || sink == null) {
            result.error("terminal_closed", "Reopen the terminal and try again.", null)
            return
        }
        val request = Command(id)
        if (!active.compareAndSet(null, request)) {
            result.error("terminal_busy", "Wait for the current command or stop it first.", null)
            return
        }
        work.execute { execute(request, command) }
        result.success(null)
    }

    fun stop(id: String?, result: MethodChannel.Result) {
        active.get()?.takeIf { it.id == id }?.let(::cancel)
        result.success(null)
    }

    private fun cancel(request: Command) {
        request.cancelled.set(true)
        control.execute { runCatching { request.process?.destroy() } }
    }

    private fun emit(request: Command, event: Map<String, Any?>) {
        main.post {
            if (!disposed) sink?.success(event + ("id" to request.id))
        }
    }

    private fun output(request: Command, stream: String, text: String) {
        val accepted = minOf(text.length, MAX_OUTPUT_CHARACTERS - request.outputCharacters)
        if (accepted > 0) {
            emit(request, mapOf("type" to "output", "stream" to stream,
                "text" to text.substring(0, accepted)))
            request.outputCharacters += accepted
        }
        if (accepted < text.length && !request.truncated) {
            request.truncated = true
            emit(request, mapOf("type" to "truncated"))
        }
    }

    private fun read(request: Command, pipe: OutputPipe, buffer: ByteArray) {
        try {
            val size = Os.read(pipe.poll.fd, buffer, 0, buffer.size)
            pipe.ended = size == 0
            output(request, pipe.name, pipe.decoder.decode(buffer, size, pipe.ended))
        } catch (exception: ErrnoException) {
            if (exception.errno != OsConstants.EAGAIN && exception.errno != OsConstants.EINTR) {
                throw exception
            }
        }
    }

    private fun execute(request: Command, command: String) {
        val started = SystemClock.elapsedRealtime()
        val descriptors = mutableListOf<ParcelFileDescriptor>()
        var outcome = "finished"
        var exitCode: Int? = null
        var errorMessage: String? = null
        try {
            check(!request.cancelled.get() && !disposed) { "Command cancelled." }
            val service = EmbeddedShizuku.getService(context)
                ?: error("Shizuku is unavailable. Open the manager and start the service.")
            val process = service.newProcess(arrayOf("/system/bin/sh", "-c", command), null, "/")
            request.process = process
            // Close stdin immediately. Commands run independently without prompts.
            process.outputStream.use { }
            val stdout = process.inputStream.also { descriptors.add(it) }
            val stderr = process.errorStream.also { descriptors.add(it) }
            val pipes = listOf(OutputPipe("stdout", stdout), OutputPipe("stderr", stderr))
            val buffer = ByteArray(4096)
            var nextExitCheck = started
            var exitedAt: Long? = null
            while (true) {
                val now = SystemClock.elapsedRealtime()
                if (request.cancelled.get() || disposed) break
                if (now - started >= TIMEOUT_MS) {
                    outcome = "timedOut"
                    break
                }
                if (now >= nextExitCheck && exitedAt == null) {
                    if (!process.alive()) {
                        exitCode = process.exitValue()
                        exitedAt = now
                    }
                    nextExitCheck = now + 100
                }
                val readable = pipes.filter { !it.ended }
                // Detached children can retain a pipe after the shell exits.
                if (exitedAt != null && (readable.isEmpty() || now - exitedAt >= 2000)) break
                if (readable.isEmpty()) {
                    Thread.sleep(100)
                } else {
                    try {
                        Os.poll(readable.map { it.poll }.toTypedArray(), 100)
                    } catch (exception: ErrnoException) {
                        if (exception.errno != OsConstants.EINTR) throw exception
                    }
                    for (pipe in readable) {
                        if (pipe.poll.revents.toInt() != 0) read(request, pipe, buffer)
                    }
                }
            }
        } catch (exception: Exception) {
            outcome = "error"
            errorMessage = exception.message ?: "Shizuku disconnected while running the command."
        } finally {
            runCatching { request.process?.destroy() }
            descriptors.forEach { runCatching { it.close() } }
            if (request.cancelled.get() || disposed) {
                outcome = "cancelled"
                errorMessage = null
            }
            active.compareAndSet(request, null)
            emit(request, mapOf("type" to "complete", "outcome" to outcome,
                "exitCode" to exitCode, "message" to errorMessage,
                "elapsedMs" to SystemClock.elapsedRealtime() - started))
        }
    }

    fun dispose() {
        disposed = true
        sink = null
        events?.setStreamHandler(null)
        events = null
        active.get()?.let(::cancel)
        control.shutdown()
        work.shutdown()
    }

    companion object {
        private const val MAX_OUTPUT_CHARACTERS = 65536
        private const val TIMEOUT_MS = 120000L
    }
}
