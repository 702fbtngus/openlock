package dev.abdullah.openlock

import android.os.SystemClock

/** Explicitly started, bounded, in-memory capture. No disk or network writes. */
object GuardDiagnostics {
    const val REVISION = "settings-guard-3"
    private const val CAPTURE_MS = 10 * 60 * 1_000L
    private const val MAX_SNAPSHOTS = 8
    private var until = 0L
    private val snapshots = LinkedHashMap<String, String>()
    private var lastEvent = "No accessibility event captured."
    private var enforcement = "No guarded screen submitted to the monitor."
    private var lastLaunch = "No lock activity launch recorded."

    fun isCapturing(): Boolean = until > SystemClock.elapsedRealtime()

    fun start() {
        snapshots.clear()
        lastEvent = "Waiting for accessibility events."
        enforcement = "Waiting for a guarded screen."
        lastLaunch = "Waiting for a lock activity launch."
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
        appendLine("Screen snapshots: ${snapshots.size}")
        snapshots.values.forEach { appendLine(it) }
    }
}
