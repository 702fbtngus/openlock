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
    private fun row(text: String) = ScreenNode("android:id/title", text,
        className = "android.widget.TextView", clickable = true, inList = true)
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

    @Test fun acceptsPaneTitlesWithoutToolbarIds() {
        assertTrue(guarded(ScreenNode(null, null, paneTitle = "특별한 접근")))
        assertTrue(guarded(ScreenNode(null, null, paneTitle = service), toggle()))
        assertFalse(guarded(ScreenNode(null, null, paneTitle = "설치된 앱"), row(service), toggle()))
        assertFalse(guarded(ScreenNode(null, null, paneTitle = "앱"), row("특별한 접근")))
    }

    @Test fun acceptsStandaloneOemTextHeadersAndSemanticHeadings() {
        assertTrue(guarded(ScreenNode(null, "특별한 접근", className = "android.widget.TextView")))
        assertTrue(guarded(ScreenNode(null, service, className = "android.widget.TextView"), toggle()))
        assertTrue(guarded(ScreenNode(null, "Special access", className = "android.view.View", heading = true)))
    }

    @Test fun plainTextHeaderFallbackExcludesCollectionsAndClickableRows() {
        assertFalse(guarded(ScreenNode(null, "Special access", className = "android.widget.TextView", inList = true)))
        assertFalse(guarded(ScreenNode(null, "Special access", className = "android.widget.TextView", clickable = true)))
        assertFalse(guarded(ScreenNode(null, "Special access", heading = true, inList = true)))
        assertFalse(guarded(ScreenNode(null, service, className = "android.widget.TextView", inList = true), toggle()))
        assertFalse(guarded(ScreenNode(null, service, className = "android.widget.TextView", clickable = true), toggle()))
    }

    @Test fun normalizesWrappedAndBidirectionalTitles() {
        assertTrue(guarded(title("\u200eOpenLock\nuninstall\u00a0guard\u200f"), toggle()))
        assertTrue(guarded(title("Alarms\u00a0 &\nreminders")))
        assertTrue(guarded(title(service), row("사용\n중"),
            ScreenNode(null, "사용\u00a0안 함", clickable = true)))
    }

    @Test fun cachedLabelsKeepCaseAndFormattingInsensitiveMatchesExact() {
        assertTrue(guarded(title("\u200f  sPeCiAl\u202fapp\naccess  \u200e")))
        assertFalse(guarded(title("Special app access history")))
        assertTrue(guarded(title(service), row("\u200eIN\nUSE"),
            ScreenNode(null, "\u200fTURN\u00a0OFF", clickable = true)))
        assertFalse(guarded(ScreenNode(null, service, className = "android.widget.TextView"), row("IN USE"),
            ScreenNode(null, "TURN OFF shortcut", clickable = true)))
        val header = ScreenNode("com.android.settings:id/entity_header_title", "Chrome")
        assertTrue(guarded(header, toggle(), row("\u200eALLOW\nUSAGE\u00a0ACCESS")))
        assertFalse(guarded(header, toggle(), row("Allow usage access later")))
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

    @Test fun explicitServiceTitleDoesNotWaitForControlsToLoad() {
        assertTrue(guarded(title(service)))
        assertTrue(guarded(title("System Service"), serviceLabel = "System Service"))
        assertTrue(guarded(ScreenNode("android:id/action_bar_title", service)))
        assertTrue(guarded(ScreenNode(null, null, paneTitle = service)))
        assertFalse(guarded(ScreenNode(null, service, className = "android.widget.TextView")))
        assertFalse(guarded(ScreenNode(null, service, heading = true, inList = true)))
        assertFalse(guarded(ScreenNode("android:id/action_bar_title", service, inList = true)))
        assertFalse(guarded(title("Other service"), toggle(), row(service)))
        assertTrue(guarded(row(service), cls = "com.android.settings.accessibility.ToggleAccessibilityServicePreferenceFragment"))
        assertFalse(guarded(row("Other service"), cls = "com.android.settings.accessibility.ToggleAccessibilityServicePreferenceFragment"))
        assertFalse(guarded(toggle(), cls = "com.android.settings.accessibility.ToggleAccessibilityServicePreferenceFragment"))
    }

    @Test fun entryClickRequiresExactIdentityAndRejectsCollectionSources() {
        val entry = ScreenNode("android:id/title", "System Service", clickable = true)
        fun clicked(source: ScreenNode = entry, texts: List<String> = emptyList(),
                    pkg: String = settings) = UninstallGuard.isOwnServiceEntryClick(
            pkg, source, texts, "System Service")
        assertTrue(clicked())
        assertTrue(clicked(entry.copy(text = "\u200eSystem\nService\u200f")))
        assertTrue(clicked(entry.copy(text = null), listOf("System Service", "On")))
        assertFalse(clicked(entry.copy(text = "System Service shortcut")))
        assertFalse(clicked(entry.copy(text = "Another service")))
        assertFalse(clicked(entry.copy(inList = true), listOf("System Service")))
        assertFalse(clicked(pkg = "com.android.chrome"))
        // Passive list scans never use click evidence.
        assertFalse(guarded(title("Installed apps"), row("System Service"), toggle(),
            serviceLabel = "System Service"))
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

    @Test fun classFastPathRequiresDistinctiveClassInSettings() {
        assertTrue(UninstallGuard.isSpecialAccessScreenClass(settings,
            "com.android.settings.Settings\$AppDrawOverlaySettingsActivity"))
        assertFalse(UninstallGuard.isSpecialAccessScreenClass("com.android.chrome",
            "com.android.settings.Settings\$AppDrawOverlaySettingsActivity"))
        for (cls in listOf(null, subSettings, "com.android.settings.NotSpecialAccessSettings",
            "com.android.settings.Settings\$ManageApplicationsActivity",
            "com.android.settings.accessibility.ToggleAccessibilityServicePreferenceFragment")) {
            assertFalse(cls, UninstallGuard.isSpecialAccessScreenClass(settings, cls))
        }
    }

    @Test fun usesOemLocalizedResourceOnlyAsPageTitle() {
        val translation = "Autorisations spéciales"
        assertTrue(guarded(title(translation), localized = setOf(translation)))
        assertFalse(guarded(title("Apps"), row(translation), localized = setOf(translation)))
    }

    @Test fun localizedLabelCacheUpdatesWhenResourceSetChanges() {
        val localized = mutableSetOf("Autorisations\nspéciales")
        assertTrue(guarded(title("\u200eAUTORISATIONS\u00a0SPÉCIALES"), localized = localized))
        localized.clear()
        localized.add("Sonderzugriff")
        assertTrue(guarded(title("sonderzugriff"), localized = localized))
        assertFalse(guarded(title("Autorisations spéciales"), localized = localized))
        assertFalse(guarded(title("Sonderzugriff")))
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
        assertTrue(guarded(title(service))) // Explicit title is enough before controls load.
        assertTrue(guarded(title(service), toggle())) // Later content event.
        assertFalse(guarded(title("Installed apps"), row(service), toggle()))
        assertTrue(guarded(title("Special access")))
        assertFalse(guarded(title("Apps"), row("Special access")))
    }

    @Test fun watchdogSnapshotDoesNotNeedWindowEventMetadata() {
        // The watchdog can see the new tree before its window/click event is
        // delivered, so it deliberately supplies no inferred activity class.
        assertTrue(guarded(title("System Service"), cls = null, serviceLabel = "System Service"))
        assertTrue(guarded(ScreenNode(null, null, paneTitle = "System Service"),
            cls = null, serviceLabel = "System Service"))
        assertTrue(guarded(title("Special access"), row("OpenLock"), cls = null))
        assertTrue(guarded(title("Draw over other apps"), row("Chrome"), cls = null))

        // Returning to the list must not inherit the previous detail match.
        assertFalse(guarded(title("Installed apps"), row("System Service"), toggle(),
            cls = null, serviceLabel = "System Service"))
        assertFalse(guarded(title("Apps"), row("Special access"), cls = null))
        assertFalse(guarded(cls = null))
    }
}
