package dev.abdullah.openlock

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One opt-in capture owns one sampler. It must not depend on the main looper
 * to notice a stall, sample it, or reach the ten-minute deadline. */
internal class GuardStallWatchdog(
    private val deadline: Long,
    private val context: () -> Context,
) {
    data class Context(val summary: String, val readThread: Thread? = null)

    private val mainLooper = Looper.getMainLooper()
    private val mainHandler = Handler(mainLooper)
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "OpenLock-stall-sampler").apply { isDaemon = true }
    }
    private val heartbeat = GuardHeartbeat()
    private val lock = Any()
    private val records = ArrayDeque<String>()
    @Volatile private var running = false
    @Volatile private var status = "not started"
    @Volatile private var pulseStatus = "no sampler poll yet"
    @Volatile private var lastMainAck = "none"
    private var pendingMain: Runnable? = null // Protected by lock; at most one queued probe.
    private var sampleCount = 0
    private var recoveryCount = 0
    private var lastPollAt: Long? = null // Sampler thread only.
    private var maxPollGap = 0L

    fun start() {
        synchronized(lock) {
            running = true
            status = "running: intervalMs=100 thresholdMs=250 repeatMs=500 clock=uptime"
            executor.scheduleWithFixedDelay({
                try {
                    poll()
                } catch (failure: RuntimeException) {
                    // Diagnostic failures must not terminate the periodic task.
                    remember("Sampler error: ${failure.javaClass.simpleName}")
                }
            }, 0L, 100L, TimeUnit.MILLISECONDS)
        }
    }

    fun stop(reason: String = "manual stop") {
        synchronized(lock) {
            if (!running) return
            running = false
            status = "stopped: $reason"
            pendingMain?.let { mainHandler.removeCallbacks(it) }
            pendingMain = null
            executor.shutdownNow()
        }
    }

    private fun remember(record: String, sample: Boolean = false, recovery: Boolean = false) {
        synchronized(lock) {
            if (!running) return
            if (sample) sampleCount++
            if (recovery) recoveryCount++
            records.addLast(record)
            while (records.size > 24) records.removeFirst()
        }
    }

    private fun poll() {
        if (!running) return
        if (SystemClock.elapsedRealtime() >= deadline) {
            stop("10-minute timeout")
            return
        }
        val now = SystemClock.uptimeMillis()
        val gap = lastPollAt?.let { now - it }
        lastPollAt = now
        maxPollGap = maxOf(maxPollGap, gap ?: 0L)
        pulseStatus = "lastPollAtElapsedMs=${SystemClock.elapsedRealtime()} " +
            "samplerGapUptimeMs=${gap ?: "first"} maxSamplerGapUptimeMs=$maxPollGap"
        val decision = heartbeat.poll(now)
        decision.recovered?.let { probe ->
            remember("Main probe recovered: probe=#${probe.id} atElapsedMs=${SystemClock.elapsedRealtime()} " +
                "postedAtUptimeMs=${probe.postedAt} ackAtUptimeMs=${probe.acknowledgedAt} " +
                "ackDelayMs=${probe.acknowledgedAt!! - probe.postedAt} " +
                "(a recovery record alone has no stack from the blocked interval)", recovery = true)
        }
        decision.post?.let { probe ->
            val callback = Runnable {
                if (running) {
                    probe.acknowledge(SystemClock.uptimeMillis())
                    lastMainAck = "probe=#${probe.id} atElapsedMs=${SystemClock.elapsedRealtime()} " +
                        "ackDelayMs=${probe.acknowledgedAt!! - probe.postedAt}"
                }
            }
            synchronized(lock) {
                if (!running) return
                pendingMain = callback
                if (!mainHandler.post(callback)) stop("main probe post rejected")
            }
        }
        val probe = decision.sample ?: return
        if (!running || probe.acknowledgedAt != null) return
        val sampledAt = SystemClock.elapsedRealtime()
        val details = context()
        // Never hold the history/lifecycle lock while reading another thread's
        // stack. Stop/report/main-thread acknowledgements must remain independent.
        val stacks = buildString {
            appendLine(threadStack("main", mainLooper.thread))
            details.readThread?.takeIf { it !== mainLooper.thread }?.let {
                appendLine(threadStack("screen-read worker", it))
            }
        }
        remember(buildString {
            appendLine("Main-thread stall sample: probe=#${probe.id} atElapsedMs=$sampledAt " +
                "postedAtUptimeMs=${probe.postedAt} pendingForMs=${now - probe.postedAt} " +
                "samplerGapUptimeMs=${gap ?: "first"} ackAtEndUptimeMs=${probe.acknowledgedAt}")
            appendLine(details.summary)
            append(stacks)
        }, sample = true)
    }

    private fun threadStack(label: String, thread: Thread): String = buildString {
        appendLine("$label: name=${thread.name} state=${thread.state} " +
            "atElapsedMs=${SystemClock.elapsedRealtime()}")
        try {
            val frames = thread.stackTrace
            if (frames.isEmpty()) appendLine("  <no Java frames available>")
            frames.take(48).forEach { appendLine("  at $it") }
            if (frames.size > 48) appendLine("  ... ${frames.size - 48} more frames")
        } catch (failure: RuntimeException) {
            appendLine("  <stack unavailable: ${failure.javaClass.simpleName}>")
        }
    }

    fun report(): String {
        val snapshot = synchronized(lock) {
            Triple(status, "samples=$sampleCount recoveredProbes=$recoveryCount retained=${records.size}/24", records.toList())
        }
        return buildString {
            appendLine("Main-thread stall sampler: ${snapshot.first}; ${snapshot.second}")
            appendLine("Sampler heartbeat: $pulseStatus")
            appendLine("Last main probe acknowledgement: $lastMainAck")
            appendLine("Stacks are samples, not proof of a lock owner. Uptime excludes deep sleep; " +
                "sampler scheduling gaps and acknowledgement during sampling can affect interpretation.")
            snapshot.third.forEach { appendLine(it) }
        }
    }
}
