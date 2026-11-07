package dev.abdullah.openlock

/** Minimal secure-settings port: no shell, hidden API, or other service reset. */
internal interface AccessibilityRestoreSettings {
    fun services(): String?
    fun masterEnabled(): Boolean
    fun putServices(value: String): Boolean
    fun enableMaster(): Boolean
}

internal class AccessibilityRestoreLogic(private val ownComponent: String) {
    enum class Result { ALREADY_CONFIGURED, SETTINGS_RESTORED, CANCELLED, SERVICE_WRITE_REJECTED, MASTER_WRITE_REJECTED }

    private fun canonical(component: String): String {
        val value = component.trim()
        val slash = value.indexOf('/')
        if (slash < 1) return value
        val pkg = value.substring(0, slash)
        val cls = value.substring(slash + 1)
        return "$pkg/${if (cls.startsWith('.')) pkg + cls else cls}"
    }

    fun isListed(raw: String?): Boolean = raw?.split(':')?.any {
        canonical(it) == canonical(ownComponent)
    } == true

    /** Keep every other entry, including entries this version cannot interpret. */
    fun withOwnService(raw: String?): String = when {
        isListed(raw) -> raw!!
        raw.isNullOrBlank() -> ownComponent
        raw.endsWith(':') -> raw + ownComponent
        else -> "$raw:$ownComponent"
    }

    fun restore(settings: AccessibilityRestoreSettings, allowed: () -> Boolean): Result {
        if (!allowed()) return Result.CANCELLED
        if (isListed(settings.services()) && settings.masterEnabled()) return Result.ALREADY_CONFIGURED
        if (!allowed()) return Result.CANCELLED
        // Re-read at the write boundary, rather than overwriting a list captured
        // before another app/user changed it. Secure Settings has no public CAS.
        val latest = settings.services()
        if (!isListed(latest)) {
            if (!allowed()) return Result.CANCELLED
            if (!settings.putServices(withOwnService(latest))) return Result.SERVICE_WRITE_REJECTED
        }
        if (!allowed()) return Result.CANCELLED
        if (!settings.masterEnabled()) {
            if (!allowed()) return Result.CANCELLED
            if (!settings.enableMaster()) return Result.MASTER_WRITE_REJECTED
        }
        return Result.SETTINGS_RESTORED
    }
}
