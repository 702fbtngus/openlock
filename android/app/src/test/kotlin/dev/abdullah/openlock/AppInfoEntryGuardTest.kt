package dev.abdullah.openlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Metadata fixtures, not captured One UI trees. OEM behaviour needs device tests. */
class AppInfoEntryGuardTest {
    private val settings = UninstallGuard.SETTINGS_PACKAGE
    private val launcher = AppInfoEntryGuard.SAMSUNG_LAUNCHER
    private val own = "dev.abdullah.openlock"
    private val host = "com.android.settings.SubSettings"
    private fun state() = AppInfoEntryGuard("OpenLock", own)
    private fun node(text: String? = null, cls: String = "android.widget.TextView") = ScreenNode(null, text, className = cls)
    private fun apps(label: String = "OpenLock") = listOf(
        ScreenNode(null, "애플리케이션", inToolbar = true),
        ScreenNode("com.android.settings:id/app_name", label, className = "android.widget.TextView",
            clickable = true, inList = true),
    )
    private fun rowClick(guard: AppInfoEntryGuard, text: String = "OpenLock", id: Int = 12,
                         cls: String = "android.widget.LinearLayout") =
        guard.clicked(settings, id, host, node(cls = cls), listOf(text), 1_000)
    private fun longClick(guard: AppInfoEntryGuard, text: String = "OpenLock", now: Long = 1_000,
                          pkg: String = launcher, cls: String = "android.widget.TextView") =
        guard.longClicked(pkg, node(cls = cls), listOf(text), now)
    private fun info(guard: AppInfoEntryGuard, text: String = "앱 정보", now: Long = 1_200) =
        guard.clicked(launcher, 21, "android.widget.PopupWindow", node(cls = "android.widget.ImageButton"), listOf(text), now)

    @Test fun anActualOwnAppRowClickNeedsAConfirmedAppsListInTheSameWindow() {
        val guard = state()
        assertNull(rowClick(guard))
        guard.observeScreen(settings, host, 12, apps())
        assertEquals(AppInfoEntryGuard.Entry.SETTINGS_APP_ROW, rowClick(guard))
        assertNull(rowClick(guard, id = 13))
        assertNull(rowClick(guard, text = "Chrome"))
        assertNull(rowClick(guard, text = "OpenLock Pro"))
        assertNull(rowClick(guard, cls = "android.widget.EditText"))
        assertEquals(AppInfoEntryGuard.Entry.SETTINGS_APP_ROW, rowClick(guard, text = own))
    }

    @Test fun passiveAppsListsRemainUnguarded() {
        assertTrue(UninstallGuard.isOwnAppInAppsList(settings, host, apps(), "OpenLock", own))
        assertFalse(UninstallGuard.isGuardedScreen(settings, host, apps(), "OpenLock", own, "System Service"))
        assertFalse(UninstallGuard.isOwnAppInAppsList(settings, host, apps("OpenLock Pro"), "OpenLock", own))
    }

    @Test fun exactAppsClassSupportsOemListsBeforeAToolbarAppears() {
        val guard = state()
        guard.observeScreen(settings, "com.android.settings.Settings\$ManageApplicationsActivity", 12, apps().drop(1))
        assertEquals(AppInfoEntryGuard.Entry.SETTINGS_APP_ROW, rowClick(guard))
    }

    @Test fun searchAndOtherListsCannotEstablishAnOwnAppRowContext() {
        val guard = state()
        guard.observeScreen(settings, "com.samsung.android.settings.search.SearchActivity", 12, apps())
        assertNull(rowClick(guard))
        guard.observeScreen(settings, host, 12, apps().map { if (it.inToolbar) it.copy(text = "Notification access") else it })
        assertNull(rowClick(guard))
        guard.observeScreen("com.android.chrome", host, 12, apps())
        assertNull(rowClick(guard))
        guard.observeScreen(settings, host, 12, apps() + ScreenNode("com.android.settings:id/entity_header_title", "OpenLock"))
        assertNull(rowClick(guard))
    }

    @Test fun aNewWindowOrNonAppsSnapshotInvalidatesTheRowContext() {
        val guard = state()
        guard.observeScreen(settings, host, 12, apps())
        guard.windowChanged(settings)
        assertNull(rowClick(guard))
        guard.observeScreen(settings, host, 12, apps())
        guard.observeScreen(settings, host, 12, emptyList())
        assertNull(rowClick(guard))
    }

    @Test fun ownIconLongPressThenInfoIsTheOnlyLauncherFastPath() {
        val guard = state()
        assertNull(info(guard))
        assertTrue(longClick(guard))
        assertTrue(guard.hasLauncherContext(1_100))
        guard.windowChanged(launcher) // The shortcut popup may have a different ID.
        assertEquals(AppInfoEntryGuard.Entry.LAUNCHER_APP_INFO, info(guard))
        assertNull(info(guard)) // Consumed, not a reusable launcher authorization.
    }

    @Test fun longPressingAnotherIconOrAnInputCannotArmTheContext() {
        val guard = state()
        assertTrue(longClick(guard))
        assertFalse(longClick(guard, "Chrome", 1_100))
        assertNull(info(guard))
        assertFalse(longClick(guard, cls = "android.widget.EditText"))
        assertFalse(longClick(guard, "OpenLock Pro"))
        assertFalse(longClick(guard, pkg = "com.example.launcher"))
        assertNull(info(guard))
    }

    @Test fun clickingAnotherShortcutConsumesTheOwnIconEvidence() {
        val guard = state()
        assertTrue(longClick(guard))
        assertNull(info(guard, text = "Remove"))
        assertNull(info(guard))
    }

    @Test fun expiredEvidenceAndExitingTheLauncherCannotMatchAnotherAppsInfoAction() {
        val guard = state()
        assertTrue(longClick(guard))
        assertNull(info(guard, now = 6_000))
        assertTrue(longClick(guard, now = 10_000))
        guard.windowChanged("com.android.chrome")
        assertNull(info(guard, now = 10_100))
    }

    @Test fun delayedRootSnapshotsDoNotOverrideTheExplicitLongClickEvidence() {
        val guard = state()
        assertTrue(longClick(guard))
        guard.observeScreen(settings, host, 12, apps())
        assertEquals(AppInfoEntryGuard.Entry.LAUNCHER_APP_INFO, info(guard))
    }

    @Test fun launcherActionsSupportEnglishKoreanAndDescriptionMetadata() {
        for (label in listOf("App info", "App information", "정보", "앱 정보", "\u200eAPP\nINFO")) {
            val guard = state()
            assertTrue(longClick(guard, own))
            assertEquals(AppInfoEntryGuard.Entry.LAUNCHER_APP_INFO,
                guard.clicked(launcher, 21, null, node(label, "android.widget.ImageView"), emptyList(), 1_200))
        }
    }

    @Test fun serviceShutdownClearsBothKindsOfEvidence() {
        val guard = state()
        guard.observeScreen(settings, host, 12, apps())
        assertTrue(longClick(guard))
        guard.clear()
        assertNull(rowClick(guard))
        assertNull(info(guard))
    }

    @Test fun samsungAppsListWithoutATitleStillSupportsTheOwnRowClick() {
        val guard = state()
        val capturedStructure = listOf(
            ScreenNode("com.android.settings:id/apps_list", null,
                className = "androidx.recyclerview.widget.RecyclerView", inList = true),
            ScreenNode("android:id/title", "OpenLock", className = "android.widget.TextView",
                clickable = true, inList = true),
            ScreenNode("com.android.settings:id/action_bar", null, inToolbar = true),
            ScreenNode(null, "상위 메뉴로 이동", className = "android.widget.ImageButton", clickable = true, inToolbar = true),
        )
        guard.observeScreen(settings, host, 12, capturedStructure)
        assertEquals(AppInfoEntryGuard.Entry.SETTINGS_APP_ROW, rowClick(guard, text = "OpenLock,"))
        assertEquals(AppInfoEntryGuard.Entry.SETTINGS_APP_ROW, rowClick(guard, text = "\u200eOpenLock,  \u200f"))
        assertNull(rowClick(guard, text = "OpenLock, shortcut"))
        assertNull(rowClick(guard, text = "OpenLock Pro,"))
        assertFalse(UninstallGuard.isGuardedScreen(settings, host, capturedStructure,
            "OpenLock", own, "System Service"))
    }

    @Test fun anAppsListIdWithoutTheActualListOrOwnRowIsNotEnough() {
        val guard = state()
        val marker = ScreenNode("com.android.settings:id/apps_list", null,
            className = "androidx.recyclerview.widget.RecyclerView", inList = true)
        guard.observeScreen(settings, host, 12, listOf(marker))
        assertNull(rowClick(guard))
        guard.observeScreen(settings, host, 12, listOf(marker.copy(className = "android.widget.TextView")) + apps().drop(1))
        assertNull(rowClick(guard))
    }
}
