package dev.abdullah.openlock

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class PhoneLockScheduleTest {
    private fun at(day: Int, hour: Int, minute: Int = 0): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone("Asia/Seoul")).apply {
            clear()
            set(2026, Calendar.NOVEMBER, day, hour, minute)
        }

    private fun phone(id: String = "p", start: Int = 22 * 60, end: Int = 6 * 60) = JSONObject()
        .put("id", id).put("lockPhone", true).put("enabled", true)
        .put("weekdays", JSONArray(listOf(5, 6, 7)))
        .put("startMinutes", start).put("endMinutes", end)
        .put("packages", JSONArray(listOf("com.stale-selection")))

    @Test fun overnightOccurrenceIsStableAcrossMidnightAndEndsOnTime() {
        val schedules = JSONArray().put(phone())
        val friday = LockLogic.activePhoneScheduleKeys(schedules, at(6, 23))
        assertFalse(friday.isEmpty())
        assertEquals(friday, LockLogic.activePhoneScheduleKeys(schedules, at(7, 5, 59)))
        assertTrue(LockLogic.activePhoneScheduleKeys(schedules, at(7, 6)).isEmpty())
        val saturday = LockLogic.activePhoneScheduleKeys(schedules, at(7, 23))
        assertEquals(1, saturday.size)
        assertFalse(friday == saturday)
    }

    @Test fun phoneModeDoesNotTreatSavedAppSelectionsAsAppLocks() {
        val legacy = JSONObject().put("id", "apps").put("enabled", true)
            .put("weekdays", JSONArray(listOf(5))).put("startMinutes", 0).put("endMinutes", 0)
            .put("packages", JSONArray(listOf("com.legacy")))
        val schedules = JSONArray().put(phone()).put(legacy)
        assertEquals(setOf("com.legacy"), LockLogic.scheduledLockedPackages(schedules, at(6, 23)))
        assertEquals(1, LockLogic.activePhoneScheduleKeys(schedules, at(6, 23)).size)
    }

    @Test fun overlappingDisabledAndAllDayPhoneSchedules() {
        val a = phone("a", 0, 0)
        val b = phone("b", 21 * 60, 23 * 60)
        val schedules = JSONArray().put(a).put(b)
        assertEquals(2, LockLogic.activePhoneScheduleKeys(schedules, at(6, 22)).size)
        assertEquals(1, LockLogic.activePhoneScheduleKeys(schedules, at(6, 23)).size)
        a.put("enabled", false)
        assertTrue(LockLogic.activePhoneScheduleKeys(schedules, at(6, 23)).isEmpty())
        assertTrue(LockLogic.activePhoneScheduleKeys(schedules, at(9, 22)).isEmpty()) // Monday
    }
}
