package dev.abdullah.openlock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorRestartPolicyTest {
    private fun restart(enabled: Boolean? = null, pin: Boolean = true,
                        apps: Boolean = false, schedules: Boolean = false,
                        guard: Boolean = false) = MonitorRestartPolicy.shouldRestart(
        enabled, pin, apps, schedules, guard)

    @Test fun restoresExplicitlyEnabledProtectionEvenWithoutSelectedApps() {
        assertTrue(restart(enabled = true))
    }

    @Test fun neverOverridesAnExplicitStop() {
        assertFalse(restart(enabled = false, apps = true, schedules = true, guard = true))
    }

    @Test fun migratesGuardOnlyInstalls() {
        assertTrue(restart(guard = true))
        assertTrue(restart(apps = true))
        assertTrue(restart(schedules = true))
        assertFalse(restart())
    }

    @Test fun doesNotRestoreWithoutAPin() {
        assertFalse(restart(enabled = true, pin = false))
        assertFalse(restart(pin = false, apps = true, schedules = true, guard = true))
    }
}
