package dev.abdullah.openlock

/** Short-lived evidence for an app-info entry. No PIN/session or tree I/O here. */
class AppInfoEntryGuard(private val ownLabel: String, private val ownPackage: String) {
    enum class Entry { SETTINGS_APP_ROW, LAUNCHER_APP_INFO }
    private var appsWindowId: Int? = null
    private var launcherUntil: Long? = null

    fun windowChanged(pkg: String?) {
        appsWindowId = null
        if (pkg != SAMSUNG_LAUNCHER) launcherUntil = null
    }

    fun observeScreen(pkg: String?, cls: String?, windowId: Int, nodes: List<ScreenNode>) {
        appsWindowId = windowId.takeIf { it >= 0 && pkg != null &&
            UninstallGuard.isOwnAppInAppsList(pkg, cls, nodes, ownLabel, ownPackage) }
    }

    /** Long-press alone must still allow moving icons and opening shortcuts. */
    fun longClicked(pkg: String?, source: ScreenNode, texts: List<String>, now: Long): Boolean {
        launcherUntil = null
        if (pkg != SAMSUNG_LAUNCHER || source.className !in ICON_CLASSES || !namesOwnApp(source, texts)) return false
        launcherUntil = now + LAUNCHER_CONTEXT_MS
        return true
    }

    fun hasLauncherContext(now: Long): Boolean = launcherUntil?.let { now < it } == true

    /** Call for an actual click only; the caller supplies immutable event metadata. */
    fun clicked(pkg: String?, windowId: Int, windowClass: String?, source: ScreenNode,
                texts: List<String>, now: Long): Entry? {
        if (pkg == SAMSUNG_LAUNCHER) {
            val armed = hasLauncherContext(now)
            launcherUntil = null // Every click consumes the evidence, even another shortcut.
            return Entry.LAUNCHER_APP_INFO.takeIf { armed && source.className in INFO_ACTION_CLASSES &&
                (UninstallGuard.isAppInfoAction(source.text) || texts.any(UninstallGuard::isAppInfoAction)) }
        }
        return Entry.SETTINGS_APP_ROW.takeIf { pkg == UninstallGuard.SETTINGS_PACKAGE && windowId >= 0 &&
            appsWindowId == windowId && UninstallGuard.isSettingsEntryClickSource(source, windowClass) &&
            (UninstallGuard.isOwnAppRowClickText(source.text, ownLabel, ownPackage) ||
                texts.any { UninstallGuard.isOwnAppRowClickText(it, ownLabel, ownPackage) }) }
    }

    fun clear() {
        appsWindowId = null
        launcherUntil = null
    }

    private fun namesOwnApp(source: ScreenNode, texts: List<String>): Boolean =
        UninstallGuard.isOwnAppName(source.text, ownLabel, ownPackage) ||
            texts.any { UninstallGuard.isOwnAppName(it, ownLabel, ownPackage) }

    companion object {
        const val SAMSUNG_LAUNCHER = "com.sec.android.app.launcher"
        private const val LAUNCHER_CONTEXT_MS = 5_000L
        private val ICON_CLASSES = setOf("android.widget.TextView", "android.widget.ImageView",
            "android.widget.FrameLayout", "android.view.ViewGroup", "android.view.View")
        private val INFO_ACTION_CLASSES = ICON_CLASSES + setOf("android.widget.ImageButton", "android.widget.Button")
    }
}
