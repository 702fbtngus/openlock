package dev.abdullah.openlock

import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure timestamp arithmetic: no Android clock or runtime is invoked. */
class GuardTimingTest {
    private val event = GuardDiagnostics.EventTiming(3, "com.android.settings", 44, 32, 1_000, 800)

    @Test fun separatesEventDeliveryScanQueueAndMainThreadDelay() {
        val timing = GuardDiagnostics.DetectionTiming("com.android.settings", 1_000, 1_030,
            1_037, 1_125, event)
        val result = timing.metrics(1_126, 1_135)
        for (metric in listOf("eventDeliveryMs=800", "eventToRequestMs=126", "queueWaitMs=30",
                "inspectToMatchMs=7", "scanToMainMs=88", "matchToRequestMs=89", "requestToResumeMs=9")) {
            assertTrue("Missing $metric in $result", metric in result)
        }
    }

    @Test fun retainsSlowSamplesBeyondThePrevious250MillisecondLimit() {
        val timing = GuardDiagnostics.DetectionTiming("com.android.settings", 1_000, 1_000,
            1_007, 3_007, event)
        val result = timing.metrics(3_008, 3_020)
        assertTrue("scanToMainMs=2000" in result)
        assertTrue("matchToRequestMs=2001" in result)
        assertTrue("eventDeliveryMs=800" in result)
    }

    @Test fun supportsEventOnlyClickDetectionAndUnknownOemEventTime() {
        val timing = GuardDiagnostics.DetectionTiming("com.android.settings", 1_000, 1_000,
            1_000, 1_000, event.copy(deliveryMs = null), "service-entry-click")
        val result = timing.metrics(1_000, 1_010)
        assertTrue("source=service-entry-click" in result)
        assertTrue("eventDeliveryMs=unknown" in result)
    }
}
