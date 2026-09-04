package com.mrgreenapps.a11ypilot

import com.mrgreenapps.a11ypilot.agent.AutomationScheduler
import com.mrgreenapps.a11ypilot.data.AutomationTask
import com.mrgreenapps.a11ypilot.data.AutomationTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class AutomationSchedulerTest {
    @Test
    fun dailyScheduleMovesToTomorrowAfterTheConfiguredTime() {
        val now = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 10)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val task = AutomationTask("id", "daily", "work", trigger = AutomationTrigger.DAILY, hour = 9, minute = 30)
        val next = AutomationScheduler.nextOccurrence(task, now.timeInMillis) ?: error("missing next run")
        val calendar = Calendar.getInstance().apply { timeInMillis = next }
        assertEquals(9, calendar.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, calendar.get(Calendar.MINUTE))
        assertTrue(calendar.timeInMillis > now.timeInMillis)
        val tomorrow = (now.clone() as Calendar).apply { add(Calendar.DATE, 1) }
        assertEquals(tomorrow.get(Calendar.DAY_OF_YEAR), calendar.get(Calendar.DAY_OF_YEAR))
    }

    @Test
    fun onceScheduleUsesPersistedFutureTimestamp() {
        val expected = System.currentTimeMillis() + 60_000
        val task = AutomationTask("id", "once", "work", trigger = AutomationTrigger.ONCE, nextRunAt = expected)
        assertEquals(expected, AutomationScheduler.nextOccurrence(task, System.currentTimeMillis()))
    }

    @Test
    fun hourlyScheduleFiresAtTheMinuteWithinTheCurrentHourWhenStillAhead() {
        // At 10:05, "every hour at :15" should first fire at 10:15 (not 11:15).
        val now = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 10)
            set(Calendar.MINUTE, 5)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val task = AutomationTask("id", "hourly", "work", trigger = AutomationTrigger.HOURLY, minute = 15)
        val next = AutomationScheduler.nextOccurrence(task, now.timeInMillis) ?: error("missing next run")
        val calendar = Calendar.getInstance().apply { timeInMillis = next }
        assertEquals(10, calendar.get(Calendar.HOUR_OF_DAY))
        assertEquals(15, calendar.get(Calendar.MINUTE))
    }

    @Test
    fun monthlyScheduleClampsToLastDayOfShortMonth() {
        // In February, day-of-month 31 should clamp to the last day of February (28 in 2026),
        // not silently jump to a different day.
        val now = Calendar.getInstance().apply {
            set(Calendar.YEAR, 2026)
            set(Calendar.MONTH, Calendar.FEBRUARY)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val task = AutomationTask("id", "monthly", "work", trigger = AutomationTrigger.MONTHLY, dayOfMonth = 31)
        val next = AutomationScheduler.nextOccurrence(task, now.timeInMillis) ?: error("missing next run")
        val calendar = Calendar.getInstance().apply { timeInMillis = next }
        // February 2026 has 28 days; the 31st clamps to the 28th.
        assertEquals(Calendar.FEBRUARY, calendar.get(Calendar.MONTH))
        assertEquals(28, calendar.get(Calendar.DAY_OF_MONTH))
        assertTrue(calendar.timeInMillis > now.timeInMillis)
    }
}
