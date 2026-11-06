package dev.abdullah.openlock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneLockStateTest {
    @Test fun authenticationSurvivesPollingButNotScreenOff() {
        val state = PhoneLockState()
        state.update(setOf("tonight"))
        assertTrue(state.requiresPin)
        state.unlock()
        state.update(setOf("tonight"))
        assertFalse(state.requiresPin)
        state.screenOff()
        assertTrue(state.requiresPin)
    }

    @Test fun endingAndNextOccurrenceDoNotShareAuthentication() {
        val state = PhoneLockState()
        state.update(setOf("friday"))
        state.unlock()
        state.update(emptySet())
        assertFalse(state.requiresPin)
        state.update(setOf("saturday"))
        assertTrue(state.requiresPin)
    }

    @Test fun overlapAndConfigChangesRequireFreshAuthentication() {
        val state = PhoneLockState()
        state.update(setOf("a"))
        state.unlock()
        state.update(setOf("a", "b"))
        assertTrue(state.requiresPin)
        state.unlock()
        state.update(setOf("b"))
        assertTrue(state.requiresPin)
    }

    @Test fun lateAuthenticationCannotUnlockANewChallenge() {
        val state = PhoneLockState()
        state.update(setOf("a"))
        val beforeScreenOff = state.epoch
        state.screenOff()
        state.unlock(beforeScreenOff)
        assertTrue(state.requiresPin)
        val beforeEdit = state.epoch
        state.update(setOf("edited-a"))
        state.unlock(beforeEdit)
        assertTrue(state.requiresPin)
    }

    @Test fun unlockOutsideAScheduleDoesNotPreauthorizeTheNextOne() {
        val state = PhoneLockState()
        state.unlock()
        state.update(setOf("new"))
        assertTrue(state.requiresPin)
    }
}
