package com.mrgreenapps.a11ypilot.agent

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.mrgreenapps.a11ypilot.data.AutomationRepository
import com.mrgreenapps.a11ypilot.data.AutomationTask
import com.mrgreenapps.a11ypilot.data.AutomationTrigger
import com.mrgreenapps.a11ypilot.phoneuse.AutomationReceiver
import java.util.Calendar
import java.util.TimeZone
import kotlinx.coroutines.flow.first

/** One-shot alarm scheduling with recurrence calculated after each delivery. */
object AutomationScheduler {
    fun nextOccurrence(task: AutomationTask, nowMillis: Long = System.currentTimeMillis()): Long? {
        val now = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val candidate = Calendar.getInstance().apply {
            timeZone = TimeZone.getDefault()
            clear()
            set(Calendar.YEAR, now.get(Calendar.YEAR))
            set(Calendar.MONTH, now.get(Calendar.MONTH))
            set(Calendar.DAY_OF_MONTH, now.get(Calendar.DAY_OF_MONTH))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            set(Calendar.HOUR_OF_DAY, task.hour.coerceIn(0, 23))
            set(Calendar.MINUTE, task.minute.coerceIn(0, 59))
        }
        when (task.trigger) {
            AutomationTrigger.ONCE -> {
                if (task.nextRunAt > nowMillis) return task.nextRunAt
                candidate.add(Calendar.DAY_OF_YEAR, if (candidate.timeInMillis <= nowMillis) 1 else 0)
            }
            AutomationTrigger.HOURLY -> {
                // Fire at the task's configured minute within the current hour when that minute
                // is still ahead; otherwise roll to the same minute next hour. Previously this
                // always advanced a full hour, so "every hour at :15" set at 10:05 first fired at
                // 11:15 instead of 10:15.
                candidate.set(Calendar.MINUTE, task.minute.coerceIn(0, 59))
                candidate.set(Calendar.SECOND, 0)
                candidate.set(Calendar.MILLISECOND, 0)
                if (candidate.timeInMillis <= nowMillis) candidate.add(Calendar.HOUR_OF_DAY, 1)
            }
            AutomationTrigger.DAILY -> {
                if (candidate.timeInMillis <= nowMillis) candidate.add(Calendar.DATE, 1)
            }
            AutomationTrigger.WEEKDAYS -> {
                while (candidate.timeInMillis <= nowMillis ||
                    candidate.get(Calendar.DAY_OF_WEEK) in setOf(Calendar.SATURDAY, Calendar.SUNDAY)
                ) candidate.add(Calendar.DATE, 1)
            }
            AutomationTrigger.WEEKLY -> {
                candidate.set(Calendar.DAY_OF_WEEK, task.dayOfWeek.coerceIn(Calendar.SUNDAY, Calendar.SATURDAY))
                while (candidate.timeInMillis <= nowMillis) candidate.add(Calendar.DATE, 7)
            }
            AutomationTrigger.MONTHLY -> {
                // Clamp to the last day of the target month so day-of-month 29/30/31 keeps working
                // in short months instead of silently jumping to a different day.
                val target = task.dayOfMonth.coerceIn(1, 31)
                val lastOfMonth = candidate.getActualMaximum(Calendar.DAY_OF_MONTH)
                candidate.set(Calendar.DAY_OF_MONTH, target.coerceAtMost(lastOfMonth))
                if (candidate.timeInMillis <= nowMillis) candidate.add(Calendar.MONTH, 1)
            }
            AutomationTrigger.YEARLY -> {
                val target = task.dayOfMonth.coerceIn(1, 31)
                candidate.set(Calendar.MONTH, task.month.coerceIn(1, 12) - 1)
                val lastOfMonth = candidate.getActualMaximum(Calendar.DAY_OF_MONTH)
                candidate.set(Calendar.DAY_OF_MONTH, target.coerceAtMost(lastOfMonth))
                if (candidate.timeInMillis <= nowMillis) candidate.add(Calendar.YEAR, 1)
            }
        }
        return candidate.timeInMillis
    }

    suspend fun schedule(context: Context, task: AutomationTask): AutomationTask {
        cancel(context, task.id)
        if (!task.enabled) return task.copy(nextRunAt = 0L)
        val next = nextOccurrence(task) ?: return task.copy(nextRunAt = 0L)
        val scheduled = task.copy(nextRunAt = next)
        val manager = context.getSystemService(AlarmManager::class.java)
            ?: return scheduled
        val pending = pendingIntent(context, task.id)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && canScheduleExact(manager)) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
            } else {
                manager.set(AlarmManager.RTC_WAKEUP, next, pending)
            }
        }.onFailure {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
        }
        AutomationRepository(context).upsert(scheduled)
        return scheduled
    }

    fun cancel(context: Context, taskId: String) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        manager.cancel(pendingIntent(context, taskId))
    }

    suspend fun rescheduleAll(context: Context) {
        AutomationRepository(context).observeTasks().first().filter { it.enabled }.forEach {
            schedule(context, it)
        }
    }

    private fun canScheduleExact(manager: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()

    private fun pendingIntent(context: Context, taskId: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        taskId.hashCode(),
        Intent(context, AutomationReceiver::class.java)
            .setAction(AutomationReceiver.ACTION_RUN_TASK)
            .putExtra(AutomationReceiver.EXTRA_TASK_ID, taskId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
