package com.mrgreenapps.a11ypilot.phoneuse

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mrgreenapps.a11ypilot.EventLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Calendar
import java.util.UUID

private val Context.metisAlarmDataStore: DataStore<Preferences> by preferencesDataStore(name = "metis_alarms")

/** A system alarm owned by Metis. Only these records are ever cancelled by the agent. */
@Serializable
data class MetisAlarm(
    val id: String,
    val requestCode: Int,
    val triggerAtMillis: Long,
    val hour: Int,
    val minute: Int,
    val message: String = ""
)

/** Persists and verifies the alarms created by Metis. AlarmManager has no public enumeration API. */
object AlarmStore {
    private val key = stringPreferencesKey("alarms_json")
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        encodeDefaults = true
    }

    fun observe(context: Context): Flow<List<MetisAlarm>> = context.metisAlarmDataStore.data
        .map { prefs -> decode(prefs[key]) }
        .catch { error ->
            EventLog.append("alarm> store read failed: ${error.message}")
            emit(emptyList())
        }

    suspend fun schedule(context: Context, hour: Int, minute: Int, message: String): Result<MetisAlarm> {
        if (hour !in 0..23 || minute !in 0..59) return Result.failure(IllegalArgumentException("闹钟时间无效"))
        val manager = context.getSystemService(AlarmManager::class.java)
            ?: return Result.failure(IllegalStateException("系统闹钟服务不可用"))
        val triggerAt = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DATE, 1)
        }.timeInMillis
        val alarm = MetisAlarm(
            id = UUID.randomUUID().toString(),
            requestCode = nextRequestCode(),
            triggerAtMillis = triggerAt,
            hour = hour,
            minute = minute,
            message = message.trim().take(200)
        )
        val pending = pendingIntent(context, alarm)
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
        val schedulingError = runCatching {
            if (exactAllowed) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
                } else {
                    manager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pending)
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            } else {
                manager.set(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }
        }.recoverCatching {
            // Some ROMs reject exact alarms even after canScheduleExactAlarms() reports true.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            } else {
                manager.set(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }
        }.exceptionOrNull()
        if (schedulingError != null) {
            pending.cancel()
            EventLog.append("alarm> schedule failed: ${schedulingError.message}")
            return Result.failure(IllegalStateException("设置闹钟失败：${schedulingError.message}"))
        }

        // PendingIntent is the only platform-level existence check available to apps.
        if (!pendingExists(context, alarm)) {
            EventLog.append("alarm> schedule returned but pending intent was not found")
            return Result.failure(IllegalStateException("系统未确认闹钟已登记"))
        }
        return try {
            context.metisAlarmDataStore.edit { prefs ->
                val current = decode(prefs[key]).filterNot { it.id == alarm.id }
                prefs[key] = json.encodeToString((current + alarm).takeLast(MAX_RECORDS))
            }
            EventLog.append("alarm> scheduled id=${alarm.id} at=$triggerAt")
            Result.success(alarm)
        } catch (error: Throwable) {
            // Do not leave a system alarm behind when its durable Metis record could not be saved.
            runCatching { manager.cancel(pending); pending.cancel() }
            EventLog.append("alarm> record write failed: ${error.message}")
            Result.failure(IllegalStateException("闹钟已回滚，记录保存失败：${error.message}"))
        }
    }

    suspend fun list(context: Context): List<MetisAlarm> = observe(context).first()

    suspend fun cancel(context: Context, id: String): Result<String> {
        val alarm = list(context).firstOrNull { it.id == id }
            ?: return Result.failure(IllegalArgumentException("未找到 Metis 闹钟：$id"))
        val manager = context.getSystemService(AlarmManager::class.java)
            ?: return Result.failure(IllegalStateException("系统闹钟服务不可用"))
        val pending = pendingIntent(context, alarm)
        runCatching { manager.cancel(pending); pending.cancel() }.getOrElse {
            return Result.failure(IllegalStateException("取消闹钟失败：${it.message}"))
        }
        if (pendingExists(context, alarm)) {
            return Result.failure(IllegalStateException("系统未确认闹钟已取消"))
        }
        removeRecords(context) { it.id == id }
        EventLog.append("alarm> cancelled id=$id")
        return Result.success(id)
    }

    suspend fun cancelAll(context: Context): Result<Int> {
        val alarms = list(context)
        var cancelled = 0
        val failed = mutableListOf<String>()
        alarms.forEach { alarm ->
            val result = cancel(context, alarm.id)
            if (result.isSuccess) cancelled++ else failed += alarm.id
        }
        return if (failed.isEmpty()) Result.success(cancelled)
        else Result.failure(IllegalStateException("已取消 $cancelled 个，仍有 ${failed.size} 个未确认取消"))
    }

    /** Remove a delivered one-shot record after AlarmReceiver has displayed it. */
    suspend fun markDelivered(context: Context, id: String) {
        if (id.isBlank()) return
        removeRecords(context) { it.id == id }
    }

    private suspend fun removeRecords(context: Context, predicate: (MetisAlarm) -> Boolean) {
        context.metisAlarmDataStore.edit { prefs ->
            prefs[key] = json.encodeToString(decode(prefs[key]).filterNot(predicate))
        }
    }

    private fun pendingIntent(context: Context, alarm: MetisAlarm, flags: Int = PendingIntent.FLAG_UPDATE_CURRENT): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            alarm.requestCode,
            Intent(context, AlarmReceiver::class.java)
                .setAction(AlarmReceiver.ACTION_METIS_ALARM)
                .putExtra(AlarmReceiver.EXTRA_ID, alarm.id)
                .putExtra(AlarmReceiver.EXTRA_MESSAGE, alarm.message),
            flags or PendingIntent.FLAG_IMMUTABLE
        )

    private fun pendingExists(context: Context, alarm: MetisAlarm): Boolean =
        runCatching { findPendingIntent(context, alarm) != null }.getOrDefault(false)

    private fun findPendingIntent(context: Context, alarm: MetisAlarm): PendingIntent? =
        runCatching {
            PendingIntent.getBroadcast(
                context,
                alarm.requestCode,
                Intent(context, AlarmReceiver::class.java)
                    .setAction(AlarmReceiver.ACTION_METIS_ALARM),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            ) as? PendingIntent
        }.getOrNull()

    private fun decode(raw: String?): List<MetisAlarm> = raw?.let {
        runCatching { json.decodeFromString<List<MetisAlarm>>(it) }.getOrDefault(emptyList())
    }.orEmpty()

    // Monotonic request codes avoid the collision risk of UUID.hashCode() (which could cancel the
    // wrong alarm when two codes collide) and keep PendingIntent request codes positive and stable.
    private val requestCodeCounter = java.util.concurrent.atomic.AtomicInteger(1)
    private fun nextRequestCode(): Int = requestCodeCounter.getAndUpdate { value ->
        if (value >= Int.MAX_VALUE - 1) 1 else value + 1
    }

    private const val MAX_RECORDS = 100
}
