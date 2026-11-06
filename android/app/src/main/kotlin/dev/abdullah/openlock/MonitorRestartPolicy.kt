package dev.abdullah.openlock

/** Explicit user intent wins; infer older installs that have no saved state. */
object MonitorRestartPolicy {
    fun shouldRestart(
        enabled: Boolean?,
        hasPin: Boolean,
        hasLockedApps: Boolean,
        hasSchedules: Boolean,
        preventUninstall: Boolean,
    ): Boolean = hasPin && (enabled ?: (hasLockedApps || hasSchedules || preventUninstall))
}
