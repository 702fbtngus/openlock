package dev.abdullah.openlock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAdminProtectionTest {
    private val settings = UninstallGuard.SETTINGS_PACKAGE
    private fun guarded(cls: String? = null, vararg nodes: ScreenNode, pkg: String = settings) =
        UninstallGuard.isGuardedScreen(pkg, cls, nodes.toList(), "OpenLock", "dev.abdullah.openlock", "System Service")

    @Test fun protectsListsAndIndividualAdminScreensWithoutAnOwnAppName() {
        for (cls in listOf("com.android.settings.DeviceAdminSettings",
                "com.android.settings.Settings\$DeviceAdminSettingsActivity", "com.android.settings.DeviceAdminAdd")) {
            assertTrue(cls, guarded(cls))
            assertTrue(UninstallGuard.isDeviceAdminScreenClass(settings, cls))
            assertFalse(guarded(cls, pkg = "com.android.chrome"))
        }
        assertFalse(guarded("com.android.settings.NotDeviceAdminSettings"))
    }

    @Test fun genericHostsRequireAnExplicitDeviceAdminPageTitle() {
        for (text in listOf("기기 관리자 앱", "기기 관리자", "Device admin apps", "Device administrators")) {
            assertTrue(guarded("com.android.settings.SubSettings", ScreenNode(null, text, inToolbar = true)))
            assertTrue(guarded("com.android.settings.SubSettings", ScreenNode(null, null, paneTitle = text)))
            assertFalse(guarded("com.android.settings.SubSettings",
                ScreenNode("android:id/title", text, className = "android.widget.TextView", clickable = true, inList = true)))
        }
        assertFalse(guarded("com.android.settings.SubSettings", ScreenNode(null, "Device admin apps history", inToolbar = true)))
    }

    @Test fun adminEntryClicksUseExactLabelsAndExcludeInputsAndCollections() {
        for (text in listOf("기기 관리자 앱", "Device admin apps", "Device administrators")) {
            assertTrue(UninstallGuard.isDeviceAdminEntryClick(settings, null,
                ScreenNode(null, null, className = "android.widget.TextView"), listOf(text)))
        }
        for (cls in listOf("android.widget.EditText", "android.widget.ListView", "android.widget.Switch")) {
            assertFalse(UninstallGuard.isDeviceAdminEntryClick(settings, null,
                ScreenNode(null, null, className = cls), listOf("Device admin apps")))
        }
        assertFalse(UninstallGuard.isDeviceAdminEntryClick("com.android.chrome", null,
            ScreenNode(null, "Device admin apps", className = "android.widget.TextView"), emptyList()))
    }
}
