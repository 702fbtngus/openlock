package dev.abdullah.openlock

/** Main-thread policy for the touch-blocking cover; never grants an unlock. */
class SettingsInspectionGate {
    enum class TimeoutAction { NONE, REQUEST_PIN, HOME }

    var active = false
        private set
    var awaitingPin = false
        private set
    private var startedAt = 0L
    private var changedAt = 0L
    private var pinRequestedAt = 0L
    private var safeStructure: Int? = null
    private var safeSince = 0L

    fun begin(now: Long) {
        // Content events must not postpone the overall timeout indefinitely.
        if (!active) {
            active = true
            startedAt = now
        }
        changedAt = now
        safeStructure = null
    }

    /** A positive match stays covered until the PIN's first draw callback. */
    fun requirePin(now: Long) {
        if (!active) begin(now)
        if (!awaitingPin) pinRequestedAt = now
        awaitingPin = true
        safeStructure = null
    }

    /** Only the newest, complete, loaded read may establish a safe screen. */
    fun allowScreen(complete: Boolean, structure: Int, readStartedAt: Long, now: Long): Boolean {
        if (!active || awaitingPin) return false
        if (!complete || readStartedAt < changedAt) {
            safeStructure = null
            return false
        }
        if (safeStructure != structure) {
            safeStructure = structure
            safeSince = now
            return false
        }
        if (now - changedAt < SETTLE_MS || now - safeSince < STABLE_MS) return false
        clear()
        return true
    }

    fun timeout(now: Long): TimeoutAction {
        if (!active) return TimeoutAction.NONE
        if (!awaitingPin && now - startedAt >= INSPECTION_TIMEOUT_MS) {
            requirePin(now)
            return TimeoutAction.REQUEST_PIN
        }
        if (awaitingPin && now - pinRequestedAt >= PIN_TIMEOUT_MS) {
            pinRequestedAt = now
            return TimeoutAction.HOME
        }
        return TimeoutAction.NONE
    }

    fun clear() {
        active = false
        awaitingPin = false
        safeStructure = null
    }

    private companion object {
        const val SETTLE_MS = 200L
        const val STABLE_MS = 100L
        const val INSPECTION_TIMEOUT_MS = 3_000L
        const val PIN_TIMEOUT_MS = 3_000L
    }
}
