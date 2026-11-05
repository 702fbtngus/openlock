package dev.abdullah.openlock

import dev.abdullah.openlock.GuardBackNavigation.Action
import org.junit.Assert.assertEquals
import org.junit.Test

/** Timing and safety rules; actual window focus/overlay behavior needs a device. */
class GuardBackNavigationTest {
    private val settings = "com.android.settings"
    private val own = "dev.abdullah.openlock"
    private fun navigation() = GuardBackNavigation(settings, own, 0L)

    @Test fun waitsForPinActivityToLeaveBeforeSendingBack() {
        val nav = navigation()
        assertEquals(Action.WAIT, nav.next(own, false, true, true, 0))
        assertEquals(Action.WAIT, nav.next(settings, true, true, true, 50))
        assertEquals(Action.BACK, nav.next(settings, true, true, false, 100))
        assertEquals(Action.WAIT, nav.next(settings, true, true, false, 150))
    }

    @Test fun releasesCoverOnlyAfterPreviousPageIsStable() {
        val nav = navigation()
        assertEquals(Action.BACK, nav.next(settings, true, true, false, 0))
        assertEquals(Action.WAIT, nav.next(settings, false, true, false, 100))
        assertEquals(Action.WAIT, nav.next(settings, false, true, false, 200))
        assertEquals(Action.WAIT, nav.next(settings, false, true, false, 350))
        assertEquals(Action.COMPLETE, nav.next(settings, false, true, false, 400))
    }

    @Test fun loadingOrGuardedFramesResetTheSafePageCheck() {
        val nav = navigation()
        nav.next(settings, true, true, false, 0)
        assertEquals(Action.WAIT, nav.next(settings, false, false, false, 200))
        assertEquals(Action.WAIT, nav.next(settings, false, true, false, 250))
        assertEquals(Action.WAIT, nav.next(settings, true, true, false, 300))
        assertEquals(Action.WAIT, nav.next(settings, false, true, false, 350))
        assertEquals(Action.WAIT, nav.next(null, false, false, false, 400))
        assertEquals(Action.WAIT, nav.next(settings, false, true, false, 450))
        assertEquals(Action.COMPLETE, nav.next(settings, false, true, false, 650))
    }

    @Test fun skipsProtectedParentWithoutUnlockingIt() {
        val nav = navigation()
        assertEquals(Action.BACK, nav.next(settings, true, true, false, 0))
        assertEquals(Action.WAIT, nav.next(settings, true, true, false, 400))
        assertEquals(Action.BACK, nav.next(settings, true, true, false, 500))
        assertEquals(Action.WAIT, nav.next(settings, false, true, false, 700))
        assertEquals(Action.COMPLETE, nav.next(settings, false, true, false, 900))
    }

    @Test fun neverSendsBackToAnUnrelatedApp() {
        val nav = navigation()
        assertEquals(Action.COMPLETE, nav.next("com.android.launcher", false, true, false, 100))
        val otherGuarded = navigation()
        assertEquals(Action.HOME, otherGuarded.next("com.android.packageinstaller", true, true, false, 100))
    }

    @Test fun limitsBackAttemptsAndFallsBackToHome() {
        val nav = navigation()
        for (time in listOf(0L, 500L, 1_000L, 1_500L)) {
            assertEquals(Action.BACK, nav.next(settings, true, true, false, time))
        }
        assertEquals(Action.WAIT, nav.next(settings, true, true, false, 2_000))
        assertEquals(Action.HOME, nav.next(settings, true, true, false, 3_000))
        assertEquals(Action.COMPLETE, nav.next("com.android.launcher", false, true, false, 3_100))
    }

    @Test fun failedHomeRestoresLockAndKeepsCoverUntilItResumes() {
        val nav = navigation()
        assertEquals(Action.HOME, nav.next(null, false, false, false, 3_000))
        assertEquals(Action.WAIT, nav.next(settings, true, true, false, 3_500))
        assertEquals(Action.RESTORE_LOCK, nav.next(settings, true, true, false, 3_700))
        assertEquals(Action.WAIT, nav.next(own, false, true, false, 3_750))
        assertEquals(Action.COMPLETE, nav.next(own, false, true, true, 3_800))
    }

    @Test fun failedActivityLaunchKeepsCoverAndAllowsHomeRecovery() {
        val nav = navigation()
        nav.next(null, false, false, false, 3_000)
        nav.next(settings, true, true, false, 3_700)
        assertEquals(Action.HOME, nav.next(settings, true, true, false, 4_700))
        assertEquals(Action.WAIT, nav.next(settings, true, true, false, 4_800))
        assertEquals(Action.COMPLETE, nav.next("com.android.launcher", false, true, false, 4_900))
    }
}
