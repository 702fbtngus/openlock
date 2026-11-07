package dev.abdullah.openlock

import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Explicitly started, bounded, in-memory capture. No disk or network writes. */
object GuardDiagnostics {
    const val REVISION = "settings-guard-8-entry3"
    private const val CAPTURE_MS = 10 * 60 * 1_000L
    private const val MAX_SNAPSHOTS = 8
    @Volatile private var until = 0L
    @Volatile private var captureGeneration = 0L
    private var captureStartedAt = 0L
    private val snapshots = LinkedHashMap<String, String>()
    @Volatile private var lastEvent = "No accessibility event captured."
    private var enforcement = "No guarded screen submitted to the monitor."
    private var lastLaunch = "No lock activity launch recorded."
    data class EventTiming(val sequence: Long, val pkg: String?, val windowId: Int,
                           val type: Int, val receivedAt: Long, val deliveryMs: Long?)

    /** Worker-only progress is immutable and independent of the main-thread histories. */
    data class ReadToken(val generation: Long, val id: Long, val request: Long,
                         val source: String, val queuedAt: Long, val startedAt: Long)

    internal data class ReadProgress(val token: ReadToken, val stage: String,
                                    val stageAt: Long, val nodesRead: Int = 0,
                                    val worker: Thread? = null) {
        fun metrics(now: Long): String = "read=#${token.id} request=${token.request} source=${token.source} " +
            "startedAtElapsedMs=${token.startedAt} ageMs=${now - token.startedAt} " +
            "queueWaitMs=${token.startedAt - token.queuedAt} stage=$stage " +
            "stageAgeMs=${now - stageAt} nodesRead=$nodesRead"
    }

    private val readSequence = AtomicLong()
    private val activeRead = AtomicReference<ReadProgress?>()
    private data class UnfinishedRead(val progress: ReadProgress, val stoppedAt: Long, val reason: String)
    // Written only on main. A stopped capture must retain a blocked read for the copied report.
    private var unfinishedRead: UnfinishedRead? = null
    // Created only when capture starts; pure timing tests need no Android looper.
    private var stallWatchdog: GuardStallWatchdog? = null

    /** Immutable provenance passed with the actual guard detection, not a
     *  "latest scan within 250 ms" guess which loses the slowest samples. */
    data class DetectionTiming(val pkg: String, val queuedAt: Long, val startedAt: Long,
                               val matchedAt: Long, val appliedAt: Long,
                               val event: EventTiming? = null, val source: String = "window-scan") {
        fun metrics(requestedAt: Long, resumedAt: Long): String = buildString {
            append("source=$source ")
            event?.let {
                append("event=#${it.sequence} eventType=${it.type} ")
                append("eventDeliveryMs=${it.deliveryMs?.toString() ?: "unknown"} ")
                append("eventToRequestMs=${requestedAt - it.receivedAt} ")
            }
            append("queueWaitMs=${startedAt - queuedAt} inspectToMatchMs=${matchedAt - startedAt} ")
            append("scanToMainMs=${appliedAt - matchedAt} matchToRequestMs=${requestedAt - matchedAt} ")
            append("requestToResumeMs=${resumedAt - requestedAt}")
        }
    }

    private data class PendingLock(val id: Long, val pkg: String, val requestedAt: Long,
                                   val scan: DetectionTiming?)
    private var pendingLock: PendingLock? = null
    private var awaitingFirstDraw: PendingLock? = null
    private var latency = "No lock timing captured."
    private var eventSequence = 0L
    private var lockSequence = 0L
    private val recentEvents = ArrayDeque<String>()
    private val recentLocks = ArrayDeque<String>()
    private val slowUsage = ArrayDeque<String>()
    private var lastUsage = "No usage poll captured."
    private var accessibilityMode = "Accessibility service has not connected in this process."
    private var lastWatch = "No Settings watchdog read captured."
    @Volatile private var watchState = "No Settings watchdog state captured."
    @Volatile private var watchStateAt = 0L
    private val watchStates = ArrayDeque<String>()
    private val recentInspections = ArrayDeque<String>()
    private val slowInspections = ArrayDeque<String>()

    // Connection state survives starting a new capture; no screen text here.
    fun accessibilityMode(summary: String) { accessibilityMode = summary }

    private fun remember(history: ArrayDeque<String>, value: String) {
        history.addLast(value)
        while (history.size > 16) history.removeFirst()
    }

    fun isCapturing(): Boolean = until > SystemClock.elapsedRealtime()

    fun start() {
        stallWatchdog?.stop("capture restarted")
        until = 0L
        captureGeneration++
        activeRead.set(null)
        unfinishedRead = null
        snapshots.clear()
        lastEvent = "Waiting for accessibility events."
        enforcement = "Waiting for a guarded screen."
        lastLaunch = "Waiting for a lock activity launch."
        pendingLock = null
        awaitingFirstDraw = null
        latency = "Waiting for a protected-screen lock."
        eventSequence = 0L
        lockSequence = 0L
        recentEvents.clear()
        recentLocks.clear()
        slowUsage.clear()
        lastUsage = "Waiting for a usage poll."
        lastWatch = "Waiting for a Settings watchdog read."
        watchState = "Waiting for a Settings watchdog state."
        watchStateAt = 0L
        watchStates.clear()
        recentInspections.clear()
        slowInspections.clear()
        captureStartedAt = SystemClock.elapsedRealtime()
        until = captureStartedAt + CAPTURE_MS
        stallWatchdog = GuardStallWatchdog(until) {
            val progress = activeRead.get()?.takeIf { it.token.generation == captureGeneration }
            GuardStallWatchdog.Context(buildString {
                appendLine("Last accessibility callback: $lastEvent")
                appendLine("Settings watchdog state: atElapsedMs=$watchStateAt $watchState")
                append("Active screen read: ${progress?.metrics(SystemClock.elapsedRealtime()) ?: "none"}")
            }, progress?.worker)
        }.also { it.start() }
    }

    fun stop() {
        stallWatchdog?.stop()
        val deadline = until
        until = 0L
        val now = SystemClock.elapsedRealtime()
        if (deadline > 0L) {
            freezeUnfinishedRead(minOf(now, deadline),
                if (now >= deadline) "10-minute timeout" else "manual stop")
        }
        captureGeneration++
        activeRead.set(null)
    }

    private fun freezeUnfinishedRead(at: Long, reason: String) {
        if (unfinishedRead != null) return
        val progress = activeRead.get()?.takeIf {
            it.token.generation == captureGeneration && it.token.startedAt <= at
        } ?: return
        unfinishedRead = UnfinishedRead(progress, at, reason)
    }

    private fun freezeExpiredRead(now: Long) {
        val deadline = until
        if (deadline > 0L && now >= deadline) freezeUnfinishedRead(deadline, "10-minute timeout")
    }

    /** Called on the screen-read worker before any potentially blocking Binder call. */
    fun readStarted(request: Long, source: String, queuedAt: Long): ReadToken? {
        val generation = captureGeneration
        if (!isCapturing()) return null
        val token = ReadToken(generation, readSequence.incrementAndGet(), request, source,
            queuedAt, SystemClock.elapsedRealtime())
        val progress = ReadProgress(token, "starting", token.startedAt, worker = Thread.currentThread())
        if (generation != captureGeneration || !isCapturing()) return null
        activeRead.set(progress)
        // A capture can be restarted between the check and publication above.
        if (generation != captureGeneration) activeRead.compareAndSet(progress, null)
        return token
    }

    /** No mutable diagnostic collections are touched from the worker. */
    fun readStage(token: ReadToken?, stage: String, nodesRead: Int = 0) {
        if (token == null || token.generation != captureGeneration || !isCapturing()) return
        while (true) {
            val previous = activeRead.get() ?: return
            if (previous.token != token || token.generation != captureGeneration) return
            val now = SystemClock.elapsedRealtime()
            if (now >= until) return
            val next = previous.copy(stage = stage, stageAt = now, nodesRead = nodesRead)
            if (activeRead.compareAndSet(previous, next)) {
                if (token.generation != captureGeneration) activeRead.compareAndSet(next, null)
                return
            }
        }
    }

    /** Called on main; an older completion must not erase a newer worker read. */
    fun readFinished(token: ReadToken?) {
        if (token == null) return
        freezeExpiredRead(SystemClock.elapsedRealtime())
        while (true) {
            val previous = activeRead.get() ?: return
            if (previous.token != token) return
            if (activeRead.compareAndSet(previous, null)) return
        }
    }

    /** Called on main when watchdog scheduling or suspension changes. */
    fun watch(summary: String) {
        val bounded = summary.take(1_500)
        if (!isCapturing() || bounded == watchState) return
        watchState = bounded
        watchStateAt = SystemClock.elapsedRealtime()
        remember(watchStates, "atElapsedMs=$watchStateAt $watchState")
    }

    fun event(summary: String, pkg: String?, windowId: Int, type: Int, sentAtUptime: Long): EventTiming? {
        if (!isCapturing()) return null
        val receivedAt = SystemClock.elapsedRealtime()
        val delay = (SystemClock.uptimeMillis() - sentAtUptime).takeIf { sentAtUptime > 0L && it >= 0L }
        val timing = EventTiming(++eventSequence, pkg, windowId, type, receivedAt, delay)
        lastEvent = "event=#${timing.sequence} receivedAtElapsedMs=$receivedAt eventDeliveryMs=$delay ${summary.take(1_500)}"
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                (pkg == AppInfoEntryGuard.SAMSUNG_LAUNCHER &&
                    (type == AccessibilityEvent.TYPE_VIEW_CLICKED || type == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED)) ||
                (pkg == UninstallGuard.SETTINGS_PACKAGE &&
                    (type == AccessibilityEvent.TYPE_VIEW_CLICKED || (delay ?: 0L) >= 100L))) {
            remember(recentEvents, lastEvent)
        }
        return timing
    }

    fun enforcement(summary: String) {
        if (isCapturing()) enforcement = summary
    }

    fun launch(summary: String) {
        if (isCapturing()) lastLaunch = summary
    }

    fun scan(pkg: String, startedAt: Long, matchedAt: Long, guarded: Boolean,
             queuedAt: Long = startedAt, event: EventTiming? = null,
             source: String = "window-scan"): DetectionTiming? {
        if (!isCapturing() || !guarded) return null
        return DetectionTiming(pkg, queuedAt, startedAt, matchedAt, SystemClock.elapsedRealtime(), event, source)
    }

    fun usagePoll(startedAt: Long, finishedAt: Long, accepted: Boolean, error: String?) {
        if (!isCapturing()) return
        val queryMs = finishedAt - startedAt
        val deliveryMs = SystemClock.elapsedRealtime() - finishedAt
        lastUsage = "atElapsedMs=$startedAt queryMs=$queryMs deliveryToMainMs=$deliveryMs accepted=$accepted error=$error"
        if (queryMs >= 100L || deliveryMs >= 100L || error != null) remember(slowUsage, lastUsage)
    }

    fun inspection(source: String, pkg: String?, windowId: Int, startedAt: Long, finishedAt: Long,
                   guarded: Boolean, rootReadMs: Long? = null, accepted: Boolean = true,
                   outcome: String = "applied", queuedAt: Long = startedAt) {
        if (!isCapturing() || startedAt < captureStartedAt) return
        val scanMs = finishedAt - startedAt
        val queueMs = startedAt - queuedAt
        val deliveryMs = SystemClock.elapsedRealtime() - finishedAt
        val walkMs = rootReadMs?.let { (scanMs - it).coerceAtLeast(0L) }
        val summary = "source=$source atElapsedMs=$startedAt package=$pkg windowId=$windowId " +
            "guarded=$guarded accepted=$accepted outcome=$outcome queueWaitMs=$queueMs " +
            "rootMs=${rootReadMs ?: "unknown"} walkMs=${walkMs ?: "unknown"} " +
            "scanMs=$scanMs deliveryToMainMs=$deliveryMs"
        remember(recentInspections, summary)
        if (source == "window-watchdog") lastWatch = summary
        if (queueMs >= 100L || scanMs >= 100L || deliveryMs >= 100L) remember(slowInspections, summary)
    }

    fun lockRequested(pkg: String, timing: DetectionTiming? = null) {
        if (!isCapturing()) return
        val now = SystemClock.elapsedRealtime()
        val scan = timing?.takeIf { it.pkg == pkg }
        pendingLock = PendingLock(++lockSequence, pkg, now, scan)
        remember(recentLocks, "#${lockSequence} request atElapsedMs=$now package=$pkg source=${scan?.source ?: "poll-or-restore"}")
    }

    fun lockResumed(pkg: String) {
        if (!isCapturing()) return
        val pending = pendingLock?.takeIf { it.pkg == pkg } ?: return
        val now = SystemClock.elapsedRealtime()
        latency = pending.scan?.metrics(pending.requestedAt, now)
            ?: "source=poll-or-restore requestToResumeMs=${now - pending.requestedAt}"
        remember(recentLocks, "#${pending.id} resumed atElapsedMs=$now package=$pkg $latency")
        awaitingFirstDraw = pending
        pendingLock = null
    }

    /** View drawing callback, not proof of pixels being presented by the GPU. */
    fun lockDrawn(pkg: String, drawnAt: Long) {
        if (!isCapturing()) return
        val pending = awaitingFirstDraw?.takeIf { it.pkg == pkg } ?: return
        val metric = "requestToFirstDrawMs=${drawnAt - pending.requestedAt}"
        latency += " $metric"
        remember(recentLocks, "#${pending.id} first-draw atElapsedMs=$drawnAt package=$pkg $metric")
        awaitingFirstDraw = null
    }

    fun snapshot(key: String, value: String) {
        if (!isCapturing()) return
        snapshots.remove(key)
        snapshots[key] = value.take(36_000)
        while (snapshots.size > MAX_SNAPSHOTS) snapshots.remove(snapshots.keys.first())
    }

    fun report(): String = buildString {
        val now = SystemClock.elapsedRealtime()
        freezeExpiredRead(now)
        val capturing = until > now
        appendLine("Capture active: $capturing (auto-stops after 10 minutes)")
        appendLine("Accessibility reads: $accessibilityMode")
        appendLine("Last event: $lastEvent")
        appendLine("Monitor decision: $enforcement")
        appendLine("Lock activity: $lastLaunch")
        appendLine("Lock timing (Activity resume/first draw callback, not GPU presentation): $latency")
        appendLine("Last usage poll (background worker): $lastUsage")
        appendLine("Last Settings watchdog read: $lastWatch")
        appendLine("Settings watchdog state: atElapsedMs=$watchStateAt $watchState")
        appendLine(stallWatchdog?.report() ?: "Main-thread stall sampler: not started")
        val read = activeRead.get()?.takeIf { capturing && it.token.generation == captureGeneration }
        appendLine("Active screen read: ${read?.metrics(now) ?: "none"}")
        unfinishedRead?.let {
            appendLine("Read unfinished at capture stop: stopAtElapsedMs=${it.stoppedAt} " +
                "reason=${it.reason} ${it.progress.metrics(it.stoppedAt)}")
        }
        appendLine("Recent lock requests/resumes: ${recentLocks.size}")
        recentLocks.forEach { appendLine(it) }
        appendLine("Recent window changes (all packages) / Settings click/slow events: ${recentEvents.size}")
        recentEvents.forEach { appendLine(it) }
        appendLine("Recent Settings watchdog state changes: ${watchStates.size}")
        watchStates.forEach { appendLine(it) }
        appendLine("Slow usage polls (query or main delivery >=100ms): ${slowUsage.size}")
        slowUsage.forEach { appendLine(it) }
        appendLine("Recent screen read outcomes (including discarded reads): ${recentInspections.size}")
        recentInspections.forEach { appendLine(it) }
        appendLine("Slow screen reads (queue, scan or main delivery >=100ms): ${slowInspections.size}")
        slowInspections.forEach { appendLine(it) }
        appendLine("Screen snapshots: ${snapshots.size}")
        snapshots.values.forEach { appendLine(it) }
    }
}
