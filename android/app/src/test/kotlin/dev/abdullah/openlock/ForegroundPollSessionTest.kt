package dev.abdullah.openlock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundPollSessionTest {
    @Test fun acceptsResultsOnlyWhileStarted() {
        val session = ForegroundPollSession()
        assertFalse(session.canApply(0, 4, 4))
        val ticket = session.start()
        assertTrue(session.canApply(ticket, 4, 4))
        session.stop()
        assertFalse(session.canApply(ticket, 4, 4))
    }

    @Test fun discardsAnOldQueryAfterAWindowChange() {
        val session = ForegroundPollSession()
        val ticket = session.start()
        assertFalse(session.canApply(ticket, 4, 5))
        // The next query for the new window is usable.
        assertTrue(session.canApply(ticket, 5, 5))
    }

    @Test fun aRestartCannotReviveAPreviousInFlightResult() {
        val session = ForegroundPollSession()
        val old = session.start()
        session.stop()
        val current = session.start()
        assertFalse(session.canApply(old, 4, 4))
        assertTrue(session.canApply(current, 4, 4))
    }

    @Test fun repeatedStartInvalidatesThePreviousPollingLoop() {
        val session = ForegroundPollSession()
        val old = session.start()
        val current = session.start()
        assertFalse(session.isCurrent(old))
        assertTrue(session.isCurrent(current))
    }
}
