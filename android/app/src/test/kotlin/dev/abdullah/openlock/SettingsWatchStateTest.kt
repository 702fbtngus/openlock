package dev.abdullah.openlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsWatchStateTest {
    private fun state(graceMs: Long = 2_000L) = SettingsWatchState(
        settingsPackage = "com.android.settings",
        ownPackage = "dev.abdullah.openlock",
        transientGraceMs = graceMs,
    )

    @Test fun transientWindowsCannotArmAnUnseenSettingsWindow() {
        val watch = state()
        watch.observePackage(null, 100)
        watch.observePackage("com.android.systemui", 200)
        assertFalse(watch.isActive(200))
        assertEquals("inactive", watch.reason)
    }

    @Test fun settingsRemainActiveWithoutATransientWindow() {
        val watch = state()
        watch.observePackage("com.android.settings", 100)
        assertTrue(watch.isActive(10_000))
        assertEquals("settings", watch.reason)
    }

    @Test fun repeatedTransientEventsDoNotExtendTheFirstDeadline() {
        val watch = state()
        watch.observePackage("com.android.settings", 100)
        watch.observePackage(null, 200)
        assertTrue(watch.isActive(200))
        watch.observePackage("com.android.systemui", 2_000)
        assertTrue(watch.isActive(2_199))
        assertFalse(watch.isActive(2_200))
        assertEquals("transient-expired", watch.reason)
        watch.observePackage(null, 2_300)
        assertFalse(watch.isActive(2_300))
    }

    @Test fun settingsRecoveryClearsThePreviousTransientDeadline() {
        val watch = state()
        watch.observePackage("com.android.settings", 100)
        watch.observePackage(null, 200)
        watch.observePackage("com.android.settings", 2_000)
        assertTrue(watch.isActive(2_500))
        assertEquals("settings", watch.reason)
        watch.observePackage("com.android.systemui", 3_000)
        assertTrue(watch.isActive(4_999))
        assertFalse(watch.isActive(5_000))
        watch.observePackage("com.android.settings", 5_100)
        assertTrue(watch.isActive(9_000))
    }

    @Test fun ownAppStopsTheWatchImmediatelyEvenDuringGrace() {
        val watch = state()
        watch.observePackage("com.android.settings", 100)
        watch.observePackage(null, 200)
        watch.observePackage("dev.abdullah.openlock", 300)
        assertFalse(watch.isActive(300))
        assertEquals("own-window", watch.reason)
        watch.observePackage("com.android.systemui", 400)
        assertFalse(watch.isActive(400))
    }

    @Test fun anotherAppStopsTheWatchImmediately() {
        val watch = state()
        watch.observePackage("com.android.settings", 100)
        watch.observePackage("com.example.other", 200)
        assertFalse(watch.isActive(200))
        assertEquals("other-window", watch.reason)
    }

    @Test fun anExplicitStopPersistsUntilSettingsAreSeenAgain() {
        val watch = state()
        watch.observePackage("com.android.settings", 100)
        watch.stop("screen-off")
        watch.observePackage(null, 200)
        assertFalse(watch.isActive(200))
        assertEquals("screen-off", watch.reason)
        watch.observePackage("com.android.settings", 300)
        assertTrue(watch.isActive(300))
    }

    @Test fun zeroGraceExpiresImmediately() {
        val watch = state(graceMs = 0)
        watch.observePackage("com.android.settings", 100)
        watch.observePackage(null, 200)
        assertFalse(watch.isActive(200))
        assertEquals("transient-expired", watch.reason)
    }
}
