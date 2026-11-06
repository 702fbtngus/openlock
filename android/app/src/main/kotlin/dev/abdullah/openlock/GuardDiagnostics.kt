package dev.abdullah.openlock

import android.os.SystemClock

/** Explicitly started, bounded, in-memory capture. No disk or network writes. */
object GuardDiagnostics {
    const val REVISION = "settings-guard-5"
    private const val CAPTURE_MS = 10 * 60 * 1_000L
    private const val MAX_SNAPSHOTS = 8
    private var until = 0L
    private val snapshots = LinkedHashMap<String, String>()
    private var lastEvent = "No accessibility event captured."
    private var enforcement = "No guarded screen submitted to the monitor."
    private var lastLaunch = "No lock activity launch recorded."
    private data class Scan(val pkg: String, val queuedAt: Long, val startedAt: Long, val matchedAt: Long)
    private data class PendingLock(val pkg: String, val requestedAt: Long, val scan: Scan?)
    private var latestScan: Scan? = null
    private var pendingLock: PendingLock? = null
    private var latency = "No lock timing captured."

    fun isCapturing(): Boolean = until > SystemClock.elapsedRealtime()

    fun start() {
        snapshots.clear()
        lastEvent = "Waiting for accessibility events."
        enforcement = "Waiting for a guarded screen."
        lastLaunch = "Waiting for a lock activity launch."
        latestScan = null
        pendingLock = null
        latency = "Waiting for a protected-screen lock."
        until = SystemClock.elapsedRealtime() + CAPTURE_MS
    }

    fun stop() { until = 0L }

    fun event(summary: String) {
        if (isCapturing()) lastEvent = summary.take(1_500)
    }

    fun enforcement(summary: String) {
        if (isCapturing()) enforcement = summary
    }

    fun launch(summary: String) {
        if (isCapturing()) lastLaunch = summary
    }

    fun scan(pkg: String, startedAt: Long, matchedAt: Long, guarded: Boolean, queuedAt: Long = startedAt) {
        if (isCapturing() && guarded) latestScan = Scan(pkg, queuedAt, startedAt, matchedAt)
    }

    fun lockRequested(pkg: String) {
        if (!isCapturing()) return
        val now = SystemClock.elapsedRealtime()
        val scan = latestScan?.takeIf { it.pkg == pkg && now - it.matchedAt <= 250L }
        pendingLock = PendingLock(pkg, now, scan)
    }

    fun lockResumed(pkg: String) {
        if (!isCapturing()) return
        val pending = pendingLock?.takeIf { it.pkg == pkg } ?: return
        val now = SystemClock.elapsedRealtime()
        latency = buildString {
            pending.scan?.let {
                append("queueWaitMs=${it.startedAt - it.queuedAt} ")
                append("inspectToMatchMs=${it.matchedAt - it.startedAt} ")
                append("matchToRequestMs=${pending.requestedAt - it.matchedAt} ")
            }
            append("requestToResumeMs=${now - pending.requestedAt}")
        }
        pendingLock = null
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
        appendLine("Lock timing (Activity resume, not first displayed frame): $latency")
        appendLine("Screen snapshots: ${snapshots.size}")
        snapshots.values.forEach { appendLine(it) }
    }
}
