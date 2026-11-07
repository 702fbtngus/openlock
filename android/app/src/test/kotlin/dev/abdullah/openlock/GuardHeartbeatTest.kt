package dev.abdullah.openlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Virtual uptime; no sleeping, Android looper or real scheduling is needed. */
class GuardHeartbeatTest {
    @Test fun keepsOnlyOneProbeOutstandingWhileMainIsUnresponsive() {
        val heartbeat = GuardHeartbeat()
        val probe = heartbeat.poll(1_000).post!!
        val early = heartbeat.poll(1_249)
        assertNull(early.post)
        assertNull(early.sample)
        assertSame(probe, heartbeat.poll(1_250).sample)
        assertNull(heartbeat.poll(1_251).post)
    }

    @Test fun resamplesTheSameStallWithoutFloodingEveryPoll() {
        val heartbeat = GuardHeartbeat()
        val probe = heartbeat.poll(0).post!!
        assertSame(probe, heartbeat.poll(250).sample)
        assertNull(heartbeat.poll(749).sample)
        assertSame(probe, heartbeat.poll(750).sample)
        assertSame(probe, heartbeat.poll(1_250).sample)
    }

    @Test fun promptAcknowledgementDoesNotProduceAStallEvenIfSamplerRunsLate() {
        val heartbeat = GuardHeartbeat()
        val probe = heartbeat.poll(0).post!!
        probe.acknowledge(10)
        val decision = heartbeat.poll(2_000)
        assertNull(decision.sample)
        assertNull(decision.recovered)
        assertEquals(2L, decision.post!!.id)
    }

    @Test fun reportsLateRecoveryEvenWhenSamplerMissedTheBlockedInterval() {
        val heartbeat = GuardHeartbeat()
        val probe = heartbeat.poll(100).post!!
        probe.acknowledge(1_600)
        val decision = heartbeat.poll(1_700)
        assertSame(probe, decision.recovered)
        // Sampling a recovered thread would fabricate evidence of the past wait.
        assertNull(decision.sample)
        assertEquals(1_500L, decision.recovered!!.acknowledgedAt!! - probe.postedAt)
        assertEquals(1_700L, decision.post!!.postedAt)
    }

    @Test fun recoveryResetsSamplingWindowForTheNextStall() {
        val heartbeat = GuardHeartbeat()
        val first = heartbeat.poll(0).post!!
        heartbeat.poll(250)
        first.acknowledge(300)
        val second = heartbeat.poll(301).post!!
        assertNull(heartbeat.poll(550).sample)
        assertSame(second, heartbeat.poll(551).sample)
    }

    @Test fun acknowledgementFromAnOldProbeCannotReleaseTheNewProbe() {
        val heartbeat = GuardHeartbeat()
        val old = heartbeat.poll(0).post!!
        old.acknowledge(10)
        val current = heartbeat.poll(100).post!!
        old.acknowledge(200)
        assertSame(current, heartbeat.poll(350).sample)
    }

    @Test fun sleepDoesNotBecomeAStallWhenUptimeHasNotAdvanced() {
        val heartbeat = GuardHeartbeat()
        heartbeat.poll(5_000)
        // elapsedRealtime may have advanced by hours during suspend; uptime has not.
        assertNull(heartbeat.poll(5_000).sample)
        assertNull(heartbeat.poll(5_249).sample)
        assertEquals(5_000L, heartbeat.poll(5_250).sample!!.postedAt)
    }

    @Test fun newCaptureCannotBeAcknowledgedByAnOldCapturesCallback() {
        val oldProbe = GuardHeartbeat().poll(0).post!!
        val restarted = GuardHeartbeat()
        val newProbe = restarted.poll(100).post!!
        oldProbe.acknowledge(200)
        assertSame(newProbe, restarted.poll(350).sample)
    }
}
