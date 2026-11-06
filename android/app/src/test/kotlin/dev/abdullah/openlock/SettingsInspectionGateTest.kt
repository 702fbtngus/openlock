package dev.abdullah.openlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsInspectionGateTest {
    @Test fun aLoadedSafeFrameMustRepeatAfterTheTransitionSettles() {
        val gate = SettingsInspectionGate()
        gate.begin(1_000)
        assertFalse(gate.allowScreen(true, 10, 1_010, 1_020))
        assertFalse(gate.allowScreen(true, 10, 1_100, 1_150))
        assertTrue(gate.active)
        assertTrue(gate.allowScreen(true, 10, 1_210, 1_220))
        assertFalse(gate.active)
    }

    @Test fun anEmptyTruncatedOrFailedReadCannotReleaseTheCover() {
        val gate = SettingsInspectionGate()
        gate.begin(1_000)
        assertFalse(gate.allowScreen(true, 10, 1_020, 1_030))
        assertFalse(gate.allowScreen(false, 10, 1_250, 1_260))
        assertFalse(gate.allowScreen(true, 10, 1_400, 1_410))
        assertTrue(gate.active)
        assertTrue(gate.allowScreen(true, 10, 1_600, 1_610))
    }

    @Test fun aReadStartedBeforeTheLatestTransitionCannotEstablishSafety() {
        val gate = SettingsInspectionGate()
        gate.begin(1_000)
        assertFalse(gate.allowScreen(true, 10, 1_050, 1_060))
        gate.begin(1_100)
        assertFalse(gate.allowScreen(true, 10, 1_050, 1_400))
        assertTrue(gate.active)
        assertFalse(gate.allowScreen(true, 10, 1_410, 1_420))
        assertTrue(gate.allowScreen(true, 10, 1_550, 1_560))
    }

    @Test fun aDifferentStructureRestartsTheStabilityCheck() {
        val gate = SettingsInspectionGate()
        gate.begin(1_000)
        assertFalse(gate.allowScreen(true, 10, 1_010, 1_020))
        assertFalse(gate.allowScreen(true, 11, 1_210, 1_220))
        assertFalse(gate.allowScreen(true, 11, 1_250, 1_260))
        assertTrue(gate.allowScreen(true, 11, 1_400, 1_410))
    }

    @Test fun aPositiveMatchMustWaitForThePinEvenIfALaterReadLooksSafe() {
        val gate = SettingsInspectionGate()
        gate.begin(1_000)
        gate.requirePin(1_010)
        assertFalse(gate.allowScreen(true, 10, 1_400, 1_410))
        assertFalse(gate.allowScreen(true, 10, 1_600, 1_610))
        gate.begin(1_700)
        assertTrue(gate.awaitingPin)
        assertTrue(gate.active)
    }

    @Test fun repeatedTransitionsCannotPostponeAnInspectionTimeout() {
        val gate = SettingsInspectionGate()
        gate.begin(1_000)
        gate.begin(3_900)
        assertEquals(SettingsInspectionGate.TimeoutAction.NONE, gate.timeout(3_999))
        assertEquals(SettingsInspectionGate.TimeoutAction.REQUEST_PIN, gate.timeout(4_000))
        assertTrue(gate.active)
        assertTrue(gate.awaitingPin)
        assertEquals(SettingsInspectionGate.TimeoutAction.NONE, gate.timeout(4_100))
        assertFalse(gate.allowScreen(true, 10, 4_200, 4_210))
    }

    @Test fun aMissingPinFrameFallsBackToHomeWithoutExposingSettings() {
        val gate = SettingsInspectionGate()
        gate.begin(1_000)
        gate.requirePin(1_010)
        gate.requirePin(3_000) // Repeated detections must not postpone Home.
        assertEquals(SettingsInspectionGate.TimeoutAction.NONE, gate.timeout(4_009))
        assertEquals(SettingsInspectionGate.TimeoutAction.HOME, gate.timeout(4_010))
        assertTrue(gate.active)
        assertEquals(SettingsInspectionGate.TimeoutAction.NONE, gate.timeout(4_011))
        assertEquals(SettingsInspectionGate.TimeoutAction.HOME, gate.timeout(7_010))
    }

    @Test fun leavingSettingsOrDrawingThePinClearsTheOldHandoff() {
        val gate = SettingsInspectionGate()
        gate.begin(1_000)
        gate.requirePin(1_010)
        gate.clear()
        assertFalse(gate.active)
        assertFalse(gate.awaitingPin)
        assertEquals(SettingsInspectionGate.TimeoutAction.NONE, gate.timeout(10_000))
        gate.begin(20_000)
        assertFalse(gate.allowScreen(true, 10, 20_010, 20_020))
        assertTrue(gate.allowScreen(true, 10, 20_210, 20_220))
    }

    @Test fun anInactiveGateCannotGrantAnUnlockOrRequestAPin() {
        val gate = SettingsInspectionGate()
        assertFalse(gate.allowScreen(true, 10, 1_000, 2_000))
        assertEquals(SettingsInspectionGate.TimeoutAction.NONE, gate.timeout(10_000))
    }
}
