package com.mrgreenapps.a11ypilot.phoneuse

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.mrgreenapps.a11ypilot.R
import kotlinx.coroutines.launch

/** Delivers silent-mode alarms without opening the system clock UI. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getStringExtra(EXTRA_ID).orEmpty()
        val manager = context.getSystemService(NotificationManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            manager.getNotificationChannel(CHANNEL_ID) == null
        ) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Metis 闹钟",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
        manager.notify(
            intent.hashCode(),
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Metis 闹钟")
                .setContentText(intent.getStringExtra(EXTRA_MESSAGE).orEmpty().ifBlank { "时间到了" })
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
        )
        if (alarmId.isNotBlank()) {
            val pending = goAsync()
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try { AlarmStore.markDelivered(context.applicationContext, alarmId) }
                finally { pending.finish() }
            }
        }
    }

    companion object {
        const val ACTION_METIS_ALARM = "com.mrgreenapps.a11ypilot.action.METIS_ALARM"
        const val EXTRA_ID = "alarm_id"
        const val EXTRA_MESSAGE = "message"
        private const val CHANNEL_ID = "metis_alarms"
    }
}
