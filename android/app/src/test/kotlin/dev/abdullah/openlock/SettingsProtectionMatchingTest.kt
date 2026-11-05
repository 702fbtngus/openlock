package dev.abdullah.openlock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic layouts, NOT captured OEM trees or proof of device compatibility. */
class SettingsProtectionMatchingTest {
    private val settings = UninstallGuard.SETTINGS_PACKAGE
    private val service = "OpenLock uninstall guard"
    private val subSettings = "com.android.settings.SubSettings"

    private fun title(text: String) = ScreenNode(null, text, inToolbar = true)
    private fun row(text: String) = ScreenNode("android:id/title", text)
    private fun toggle() = ScreenNode("android:id/switch_widget", null, checkable = true)

    private fun guarded(
        vararg nodes: ScreenNode,
        cls: String? = subSettings,
        pkg: String = settings,
        serviceLabel: String = service,
        localized: Set<String> = emptySet(),
    ) = UninstallGuard.isGuardedScreen(
        windowPackage = pkg, windowClass = cls, nodes = nodes.toList(),
        ownLabel = "OpenLock", ownPackage = "dev.abdullah.openlock",
        ownServiceLabel = serviceLabel, localizedSpecialAccessTitles = localized,
    )

    @Test fun guardsServiceDetailsWithSwitchInAnyUiLanguage() {
        assertTrue(guarded(title(service), toggle()))
        assertTrue(guarded(title("서비스 이름"), toggle(), serviceLabel = "서비스 이름"))
        assertTrue(guarded(ScreenNode("android:id/action_bar_title", service), toggle()))
    }

    @Test fun guardsSamsungStyleStatusAndDisableButtonBeforeConfirmation() {
        for ((state, action) in listOf("사용 중" to "사용 안 함", "On" to "Turn off")) {
            assertTrue(guarded(title(service), row(state),
                ScreenNode(null, action, className = "android.widget.Button", clickable = true)))
        }
    }

    @Test fun serviceListIncludingOneWithRowSwitchesDoesNotMatch() {
        assertFalse(guarded(title("Installed apps"), row(service), row("On"), toggle()))
        assertFalse(guarded(title("설치된 앱"), row(service), row("사용 중"),
            ScreenNode(null, "사용 안 함", clickable = true)))
        assertFalse(guarded(row(service), toggle()))
    }

    @Test fun disableConfirmationIsAnAdditionalFallback() {
        assertTrue(guarded(ScreenNode("android:id/alertTitle", "Stop $service?"),
            ScreenNode("android:id/button1", "Stop", clickable = true)))
        assertFalse(guarded(ScreenNode("android:id/alertTitle", "Stop another service?"),
            ScreenNode("android:id/button1", "Stop", clickable = true)))
        assertFalse(guarded(row("Stop $service?"), row("Stop")))
    }

    @Test fun requiresControlsOrSpecificDetailClassAlongsideServiceIdentity() {
        assertFalse(guarded(title(service)))
        assertFalse(guarded(title("Other service"), toggle(), row(service)))
        assertTrue(guarded(row(service), cls = "com.android.settings.accessibility.ToggleAccessibilityServicePreferenceFragment"))
        assertFalse(guarded(row("Other service"), cls = "com.android.settings.accessibility.ToggleAccessibilityServicePreferenceFragment"))
        assertFalse(guarded(toggle(), cls = "com.android.settings.accessibility.ToggleAccessibilityServicePreferenceFragment"))
    }

    @Test fun specialAccessToolbarTitlesCoverTopLevelAndPermissionLists() {
        for (name in listOf("특별한 접근", "Special access", "Special app access",
            "다른 앱 위에 표시", "Draw over other apps", "알람 및 리마인더", "Alarms & reminders")) {
            assertTrue(name, guarded(title(name), row("Chrome"), row("OpenLock")))
        }
    }

    @Test fun specialAccessRowsInAppsAndSearchResultsDoNotMatch() {
        assertFalse(guarded(title("Apps"), row("Special access"), row("OpenLock")))
        assertFalse(guarded(title("Search results"), row("Draw over other apps"), row("Alarms & reminders")))
        assertFalse(guarded(title("Apps"), row("특별한 접근")))
        assertFalse(guarded(title("Special access"), pkg = "com.google.android.settings.intelligence"))
    }

    @Test fun languageIndependentClassesCoverDirectEntryAndLoading() {
        for (screen in listOf("SpecialAccessSettings", "Settings\$OverlaySettingsActivity",
            "Settings\$AppDrawOverlaySettingsActivity", "Settings\$AlarmsAndRemindersActivity",
            "Settings\$AlarmsAndRemindersAppActivity", "Settings\$AppUsageAccessSettingsActivity",
            "Settings\$AppManageExternalStorageActivity", "Settings\$AppWriteSettingsActivity",
            "Settings\$ManageAppExternalSourcesActivity", "Settings\$NotificationAccessDetailsActivity")) {
            assertTrue(screen, guarded(cls = "com.android.settings.$screen"))
        }
        assertFalse(guarded(cls = "com.android.settings.Settings\$ManageApplicationsActivity"))
        assertFalse(guarded(cls = "com.android.settings.applications.specialaccess.deviceadmin.DeviceAdminSettings"))
        assertFalse(guarded(cls = "com.android.settings.applications.specialaccess.deviceadmin.DeviceAdminAdd"))
    }

    @Test fun classMustBeExactAndInsideSettingsPackage() {
        assertFalse(guarded(title("Apps"), cls = "com.android.settings.NotSpecialAccessSettings"))
        assertFalse(guarded(cls = "com.android.settings.SpecialAccessSettings", pkg = "com.android.chrome"))
    }

    @Test fun usesOemLocalizedResourceOnlyAsPageTitle() {
        val translation = "Autorisations spéciales"
        assertTrue(guarded(title(translation), localized = setOf(translation)))
        assertFalse(guarded(title("Apps"), row(translation), localized = setOf(translation)))
    }

    @Test fun singleAppPermissionDetailsRequireSpecificControlWithHeaderAndToggle() {
        val header = ScreenNode("com.android.settings:id/entity_header_title", "Chrome")
        assertTrue(guarded(title("Chrome"), header, row("Allow display over other apps"), toggle()))
        assertTrue(guarded(title("Chrome"), header, row("Allow setting alarms and reminders"), toggle()))
        assertFalse(guarded(title("App info"), header, row("Notifications")))
        assertFalse(guarded(title("Permissions"), header, row("Allow permission"), toggle()))
        assertFalse(guarded(title("Apps"), row("Allow display over other apps"), toggle()))
    }

    @Test fun screenChangesDoNotInheritPreviousMatch() {
        assertFalse(guarded(cls = null))
        assertFalse(guarded(title(service))) // Initial frame, controls not loaded.
        assertTrue(guarded(title(service), toggle())) // Later content event.
        assertFalse(guarded(title("Installed apps"), row(service), toggle()))
        assertTrue(guarded(title("Special access")))
        assertFalse(guarded(title("Apps"), row("Special access")))
    }
}
