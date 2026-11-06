package dev.abdullah.openlock

import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import java.util.ArrayDeque

/** Explicitly started, bounded, in-memory capture. No disk or network writes. */
object GuardDiagnostics {
    const val REVISION = "settings-guard-6"
    private const val CAPTURE_MS = 10 * 60 * 1_000L
    private const val MAX_SNAPSHOTS = 8
    private var until = 0L
    private val snapshots = LinkedHashMap<String, String>()
    private var lastEvent = "No accessibility event captured."
    private var enforcement = "No guarded screen submitted to the monitor."
    private var lastLaunch = "No lock activity launch recorded."
    data class EventTiming(val sequence: Long, val pkg: String?, val windowId: Int,
                           val type: Int, val receivedAt: Long, val deliveryMs: Long?)

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

    private fun remember(history: ArrayDeque<String>, value: String) {
        history.addLast(value)
        while (history.size > 16) history.removeFirst()
    }

    fun isCapturing(): Boolean = until > SystemClock.elapsedRealtime()

    fun start() {
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
        until = SystemClock.elapsedRealtime() + CAPTURE_MS
    }

    fun stop() { until = 0L }

    fun event(summary: String, pkg: String?, windowId: Int, type: Int, sentAtUptime: Long): EventTiming? {
        if (!isCapturing()) return null
        val receivedAt = SystemClock.elapsedRealtime()
        val delay = (SystemClock.uptimeMillis() - sentAtUptime).takeIf { sentAtUptime > 0L && it >= 0L }
        val timing = EventTiming(++eventSequence, pkg, windowId, type, receivedAt, delay)
        lastEvent = "event=#${timing.sequence} receivedAtElapsedMs=$receivedAt eventDeliveryMs=$delay ${summary.take(1_500)}"
        if (pkg == UninstallGuard.SETTINGS_PACKAGE && (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                type == AccessibilityEvent.TYPE_VIEW_CLICKED || (delay ?: 0L) >= 100L)) {
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
        appendLine("Capture active: ${isCapturing()} (auto-stops after 10 minutes)")
        appendLine("Last event: $lastEvent")
        appendLine("Monitor decision: $enforcement")
        appendLine("Lock activity: $lastLaunch")
        appendLine("Lock timing (Activity resume/first draw callback, not GPU presentation): $latency")
        appendLine("Last usage poll (background worker): $lastUsage")
        appendLine("Recent lock requests/resumes: ${recentLocks.size}")
        recentLocks.forEach { appendLine(it) }
        appendLine("Recent Settings window/click/slow events: ${recentEvents.size}")
        recentEvents.forEach { appendLine(it) }
        appendLine("Slow usage polls (query or main delivery >=100ms): ${slowUsage.size}")
        slowUsage.forEach { appendLine(it) }
        appendLine("Screen snapshots: ${snapshots.size}")
        snapshots.values.forEach { appendLine(it) }
    }
}
