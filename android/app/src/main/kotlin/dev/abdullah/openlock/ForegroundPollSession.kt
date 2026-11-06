package dev.abdullah.openlock

/** Main-thread lifecycle decisions; workers only read the returned generation. */
class ForegroundPollSession {
    @Volatile private var generation = 0L
    @Volatile private var running = false

    fun start(): Long {
        generation++
        running = true
        return generation
    }

    fun stop() {
        running = false
        generation++
    }

    fun isCurrent(ticket: Long): Boolean = running && ticket == generation

    fun canApply(ticket: Long, queriedWindow: Long, currentWindow: Long): Boolean =
        isCurrent(ticket) && queriedWindow == currentWindow
}
