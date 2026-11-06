package dev.abdullah.openlock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Event metadata fixtures; real Samsung/Huawei click payloads still need testing. */
class SpecialAccessEntryClickTest {
    private val settings = UninstallGuard.SETTINGS_PACKAGE
    private val host = "com.android.settings.SubSettings"

    private fun clicked(
        text: String? = null,
        texts: List<String> = emptyList(),
        cls: String = "android.widget.TextView",
        pkg: String = settings,
        windowClass: String? = host,
        localized: Set<String> = emptySet(),
    ) = UninstallGuard.isSpecialAccessEntryClick(pkg, windowClass,
        ScreenNode(null, text, className = cls), texts, localized)

    @Test fun recognizesTopLevelMenuClicksWithoutReadingTheNewScreen() {
        for (label in listOf("특별한 접근", "특별한 권한", "Special access", "Special app access")) {
            assertTrue(label, clicked(texts = listOf(label)))
            assertTrue(label, clicked(text = label))
        }
    }

    @Test fun recognizesKnownPermissionEntriesAndOemContainerRows() {
        for (label in listOf("Draw over other apps", "Alarms & reminders", "다른 앱 위에 표시", "알람 및 리마인더")) {
            for (cls in listOf("android.widget.TextView", "android.widget.LinearLayout",
                    "android.widget.RelativeLayout", "android.widget.FrameLayout", "android.view.ViewGroup")) {
                assertTrue("$label $cls", clicked(texts = listOf(label), cls = cls))
            }
        }
        // A clicked row can belong to a list; the ListView as a whole cannot.
        assertTrue(UninstallGuard.isSpecialAccessEntryClick(settings, host,
            ScreenNode("android:id/title", "Special access", className = "android.widget.TextView",
                clickable = true, inList = true), emptyList()))
    }

    @Test fun rejectsInputWidgetsAndWholeListsEvenWhenTheirTextContainsAnEntry() {
        for (cls in listOf("android.widget.EditText", "android.widget.AutoCompleteTextView",
                "android.widget.SearchView", "android.widget.ListView", "androidx.recyclerview.widget.RecyclerView",
                "android.widget.Switch", "android.widget.CheckBox", "android.widget.Button", "android.view.View")) {
            assertFalse(cls, clicked(texts = listOf("Special access", "Apps"), cls = cls))
        }
    }

    @Test fun searchScreensKeepTheExistingDetailScreenFallback() {
        assertFalse(clicked(texts = listOf("Special access"),
            windowClass = "com.samsung.android.settings.search.SearchActivity"))
        assertFalse(clicked(text = "특별한 접근", windowClass = "com.android.settings.Settings\$SearchActivity"))
        assertFalse(clicked(texts = listOf("Special access"), pkg = "com.google.android.settings.intelligence"))
    }

    @Test fun requiresSettingsPackageAndAnExactNormalizedLabel() {
        assertFalse(clicked(texts = listOf("Special access"), pkg = "com.android.chrome"))
        assertFalse(clicked(texts = listOf("Special access history", "Apps")))
        assertFalse(clicked(text = "OpenLock"))
        assertFalse(clicked(text = "App info"))
        assertFalse(clicked())
        assertTrue(clicked(texts = listOf("\u200e  SPECIAL\u00a0APP\nACCESS\u200f")))
    }

    @Test fun usesPreviouslyLoadedLocalizedLabelsWithoutAResourceLookup() {
        val localized = setOf("Accès spécial")
        assertTrue(clicked(texts = listOf("\u200eAccès\nspécial"), localized = localized))
        assertFalse(clicked(texts = listOf("Accès spécial history"), localized = localized))
        assertFalse(clicked(texts = listOf("Accès spécial")))
    }

    @Test fun viewingTheMenuOrAnAppsListRemainsUnguarded() {
        val menu = listOf("Permission manager", "Special access", "Reset app preferences").map {
            ScreenNode("android:id/title", it, className = "android.widget.TextView", clickable = true, inList = true)
        }
        assertFalse(UninstallGuard.isGuardedScreen(settings, host, menu,
            "OpenLock", "dev.abdullah.openlock", "System Service"))
        assertFalse(UninstallGuard.isGuardedScreen(settings, host,
            listOf(ScreenNode(null, "Apps", inToolbar = true)) + menu,
            "OpenLock", "dev.abdullah.openlock", "System Service"))
    }
}
