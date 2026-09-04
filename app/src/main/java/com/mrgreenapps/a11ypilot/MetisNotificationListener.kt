package com.mrgreenapps.a11ypilot

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.mrgreenapps.a11ypilot.EventLog
import java.util.concurrent.atomic.AtomicReference

/**
 * Notification-listening channel. Lets the agent "hear" notifications (verification codes,
 * incoming messages, delivery updates) in addition to reading the screen.
 *
 * The most recent notifications are kept in a small ring buffer that the agent can query via the
 * `read_notifications` tool. This is read-only; the agent cannot post or dismiss notifications.
 */
class MetisNotificationListener : NotificationListenerService() {

    companion object {
        private const val MAX_RECENT = 50
        private val recent = AtomicReference<List<NotifEntry>>(emptyList())

        data class NotifEntry(
            val packageName: String,
            val appLabel: String,
            val title: String,
            val text: String,
            val time: Long
        )

        @Volatile
        var INSTANCE: MetisNotificationListener? = null
            private set

        fun isConnected(): Boolean = INSTANCE != null

        fun recentNotifications(limit: Int = 20): List<NotifEntry> =
            recent.get().takeLast(limit.coerceIn(1, MAX_RECENT))

        fun recentText(): String {
            val entries = recent.get()
            if (entries.isEmpty()) return "（暂无通知）"
            val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            return entries.takeLast(20).joinToString("\n") { e ->
                "- [${fmt.format(java.util.Date(e.time))}] ${e.appLabel}（${e.packageName}）：${e.title} ${e.text}".take(200)
            }
        }
    }

    override fun onListenerConnected() {
        INSTANCE = this
        EventLog.append("notif> listener connected")
    }

    override fun onListenerDisconnected() {
        if (INSTANCE === this) INSTANCE = null
        EventLog.append("notif> listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg == packageName) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }
            .getOrDefault(pkg)
        val entry = NotifEntry(pkg, label, title, text, System.currentTimeMillis())
        recent.getAndUpdate { (it + entry).takeLast(MAX_RECENT) }
    }
}
