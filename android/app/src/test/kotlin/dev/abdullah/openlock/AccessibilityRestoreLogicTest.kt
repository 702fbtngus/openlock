package dev.abdullah.openlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityRestoreLogicTest {
    private val own = "dev.abdullah.openlock/dev.abdullah.openlock.OpenLockGuardAccessibilityService"
    private val short = "dev.abdullah.openlock/.OpenLockGuardAccessibilityService"
    private val other = "reader.app/.ReaderService"
    private val logic = AccessibilityRestoreLogic(own)

    private class Fake(var raw: String?, var master: Boolean) : AccessibilityRestoreSettings {
        val writes = mutableListOf<Pair<String, String>>()
        var rejectServices = false
        var rejectMaster = false
        var reads = 0
        var beforeRead: (() -> Unit)? = null
        var afterWrite: (() -> Unit)? = null
        override fun services(): String? {
            reads++
            beforeRead?.invoke()
            return raw
        }
        override fun masterEnabled() = master
        override fun putServices(value: String): Boolean {
            writes.add("services" to value)
            if (rejectServices) return false
            raw = value
            afterWrite?.invoke()
            return true
        }
        override fun enableMaster(): Boolean {
            writes.add("master" to "1")
            if (rejectMaster) return false
            master = true
            return true
        }
    }

    @Test fun missingOwnServiceIsAddedWithoutDisablingOrRemovingOtherServices() {
        val settings = Fake(other, true)
        assertEquals(AccessibilityRestoreLogic.Result.SETTINGS_RESTORED, logic.restore(settings) { true })
        assertEquals(listOf("services" to "$other:$own"), settings.writes)
        assertTrue(settings.master)
    }

    @Test fun masterOffWithOwnServiceStillListedOnlyEnablesMaster() {
        val settings = Fake("$other:$short", false)
        logic.restore(settings) { true }
        assertEquals(listOf("master" to "1"), settings.writes)
        assertEquals("$other:$short", settings.raw)
    }

    @Test fun entirelyDisabledAccessibilityRestoresBothKeysAndThenDoesNothing() {
        val settings = Fake(null, false)
        logic.restore(settings) { true }
        assertEquals(listOf("services" to own, "master" to "1"), settings.writes)
        assertEquals(AccessibilityRestoreLogic.Result.ALREADY_CONFIGURED, logic.restore(settings) { true })
        assertEquals(2, settings.writes.size)
    }

    @Test fun healthyShortFormIsNotDuplicatedOrRewritten() {
        val settings = Fake("$other:$short", true)
        assertEquals(AccessibilityRestoreLogic.Result.ALREADY_CONFIGURED, logic.restore(settings) { true })
        assertTrue(settings.writes.isEmpty())
        assertFalse(logic.isListed("dev.other/.OpenLockGuardAccessibilityService"))
        assertFalse(logic.isListed("dev.abdullah.openlock/.OpenLockGuardAccessibilityServiceExtra"))
    }

    @Test fun missingPermissionOrDisabledProtectionDoesNotReadOrWriteSettings() {
        val settings = Fake(other, false)
        assertEquals(AccessibilityRestoreLogic.Result.CANCELLED, logic.restore(settings) { false })
        assertEquals(0, settings.reads)
        assertTrue(settings.writes.isEmpty())
    }

    @Test fun stopBetweenWritesDoesNotContinueToEnableMaster() {
        val settings = Fake(other, false)
        var enabled = true
        settings.afterWrite = { enabled = false }
        assertEquals(AccessibilityRestoreLogic.Result.CANCELLED, logic.restore(settings) { enabled })
        assertEquals(listOf("services" to "$other:$own"), settings.writes)
        assertFalse(settings.master)
    }

    @Test fun rereadsOtherServicesAtTheWriteBoundary() {
        val settings = Fake(other, true)
        val changed = "$other:new.reader/.Service"
        settings.beforeRead = { if (settings.reads == 2) settings.raw = changed }
        logic.restore(settings) { true }
        assertEquals("$changed:$own", settings.raw)
    }

    @Test fun rejectedListWriteDoesNotChangeMasterOrTryAGlobalReset() {
        val settings = Fake(other, false).apply { rejectServices = true }
        assertEquals(AccessibilityRestoreLogic.Result.SERVICE_WRITE_REJECTED, logic.restore(settings) { true })
        assertEquals(other, settings.raw)
        assertEquals(listOf("services" to "$other:$own"), settings.writes)
        assertFalse(settings.master)
    }

    @Test fun rejectedMasterWriteIsNotReportedAsSuccess() {
        val settings = Fake(other, false).apply { rejectMaster = true }
        assertEquals(AccessibilityRestoreLogic.Result.MASTER_WRITE_REJECTED, logic.restore(settings) { true })
        assertFalse(settings.master)
        assertEquals("$other:$own", settings.raw)
    }

    @Test fun unrecognizedEntriesAndTrailingDelimiterArePreserved() {
        assertEquals("legacy-entry:$other:$own", logic.withOwnService("legacy-entry:$other:"))
        assertEquals(own, logic.withOwnService(""))
    }
}
