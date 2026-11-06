package dev.abdullah.openlock

import org.junit.Assert.assertTrue
import org.junit.Test

/** Report an unfinished read without waiting for a blocking Binder call to return. */
class GuardReadProgressTest {
    @Test fun separatesWorkerQueueFromTimeBlockedAtTheCurrentReadStage() {
        val token = GuardDiagnostics.ReadToken(generation = 2, id = 7, request = 12,
            source = "window-watchdog", queuedAt = 1_000, startedAt = 1_050)
        val progress = GuardDiagnostics.ReadProgress(token, "root", stageAt = 1_055)
        val summary = progress.metrics(2_555)
        for (metric in listOf("read=#7", "request=12", "source=window-watchdog",
                "ageMs=1505", "queueWaitMs=50", "stage=root", "stageAgeMs=1500", "nodesRead=0")) {
            assertTrue("Missing $metric in $summary", metric in summary)
        }
    }

    @Test fun reportsWalkProgressAfterRootRetrieval() {
        val token = GuardDiagnostics.ReadToken(3, 8, 13, "window-scan", 2_000, 2_000)
        val progress = GuardDiagnostics.ReadProgress(token, "walk", stageAt = 2_025, nodesRead = 32)
        val summary = progress.metrics(2_180)
        for (metric in listOf("ageMs=180", "stage=walk", "stageAgeMs=155", "nodesRead=32")) {
            assertTrue("Missing $metric in $summary", metric in summary)
        }
    }
}
