package dev.abdullah.openlock

/** No unlock is granted: a touch-blocking cover stays up until navigation is safe. */
class GuardBackNavigation(
    private val targetPackage: String,
    private val ownPackage: String,
    private val startedAt: Long,
) {
    enum class Action { WAIT, BACK, HOME, RESTORE_LOCK, COMPLETE }
    private enum class Phase { BACK, HOME, RESTORE_LOCK }
    private var phase = Phase.BACK
    private var lastActionAt = startedAt
    private var unguardedSince: Long? = null
    private var backCount = 0

    fun next(
        windowPackage: String?,
        guarded: Boolean,
        contentReady: Boolean,
        lockVisible: Boolean,
        now: Long,
    ): Action {
        if (phase != Phase.BACK) {
            if (lockVisible || (windowPackage != null && windowPackage != ownPackage &&
                    windowPackage != targetPackage && !guarded)) return Action.COMPLETE
            if (phase == Phase.HOME && now - lastActionAt >= HOME_WAIT_MS) {
                phase = Phase.RESTORE_LOCK
                lastActionAt = now
                return Action.RESTORE_LOCK
            }
            // If activity launch was refused, keep the cover and retry Home.
            if (phase == Phase.RESTORE_LOCK && now - lastActionAt >= HOME_RETRY_MS) {
                lastActionAt = now
                return Action.HOME
            }
            return Action.WAIT
        }

        if (now - startedAt >= BACK_TIMEOUT_MS) {
            phase = Phase.HOME
            lastActionAt = now
            return Action.HOME
        }
        // Initially the PIN activity is still finishing. A global Back here
        // would hit that activity again rather than the Settings window.
        if (windowPackage == null || windowPackage == ownPackage || lockVisible) {
            unguardedSince = null
            return Action.WAIT
        }
        if (windowPackage != targetPackage) {
            if (!guarded) return Action.COMPLETE
            phase = Phase.HOME
            lastActionAt = now
            return Action.HOME
        }
        if (backCount == 0 || (guarded && now - lastActionAt >= BACK_INTERVAL_MS && backCount < MAX_BACKS)) {
            backCount++
            lastActionAt = now
            unguardedSince = null
            return Action.BACK
        }
        if (guarded || !contentReady || now - lastActionAt < SETTLE_MS) {
            unguardedSince = null
            return Action.WAIT
        }
        // One empty/loading frame must not expose the protected controls.
        val since = unguardedSince
        if (since == null) unguardedSince = now
        return if (since != null && now - since >= SETTLE_MS) Action.COMPLETE else Action.WAIT
    }

    companion object {
        private const val MAX_BACKS = 4
        private const val BACK_INTERVAL_MS = 500L
        private const val SETTLE_MS = 200L
        private const val BACK_TIMEOUT_MS = 3_000L
        private const val HOME_WAIT_MS = 700L
        private const val HOME_RETRY_MS = 1_000L
    }
}
