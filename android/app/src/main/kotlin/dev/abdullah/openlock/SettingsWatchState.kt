package dev.abdullah.openlock

/** Keeps an already armed Settings watch through a bounded transient window. */
class SettingsWatchState(
    private val settingsPackage: String,
    private val ownPackage: String,
    private val transientGraceMs: Long = 2_000L,
) {
    private var armed = false
    private var transientDeadline: Long? = null

    var reason: String = "inactive"
        private set

    init {
        require(transientGraceMs >= 0L)
    }

    /** [now] must use the same monotonic clock as [isActive]. */
    fun observePackage(pkg: String?, now: Long) {
        when (pkg) {
            settingsPackage -> {
                armed = true
                transientDeadline = null
                reason = "settings"
            }
            null, "com.android.systemui" -> {
                if (!isActive(now)) return
                // Repeated transient events must not keep the watch alive forever.
                if (transientDeadline == null) transientDeadline = now + transientGraceMs
                reason = "transient"
                isActive(now)
            }
            ownPackage -> stop("own-window")
            else -> stop("other-window")
        }
    }

    fun isActive(now: Long): Boolean {
        val deadline = transientDeadline
        if (armed && deadline != null && now >= deadline) stop("transient-expired")
        return armed
    }

    fun stop(reason: String) {
        armed = false
        transientDeadline = null
        this.reason = reason
    }
}
