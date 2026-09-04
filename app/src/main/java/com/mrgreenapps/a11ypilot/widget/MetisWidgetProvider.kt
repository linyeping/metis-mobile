package com.mrgreenapps.a11ypilot.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.mrgreenapps.a11ypilot.MainActivity
import com.mrgreenapps.a11ypilot.R

/**
 * Metis 桌面小组件：显示当前后台任务状态（第 N/M 步），并提供快捷入口
 * （打开 Metis / 新建对话 / 设置）。
 *
 * 小组件运行在 Launcher 进程，通过 [WidgetStatusStore] 读取任务状态；
 * 状态由 AgentExecutionService 在每次状态变化时广播 ACTION_UPDATE 触发刷新。
 */
class MetisWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id ->
            appWidgetManager.updateAppWidget(id, buildViews(context))
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_UPDATE || intent.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, MetisWidgetProvider::class.java))
            if (ids.isNotEmpty()) {
                manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_root)
                onUpdate(context, manager, ids)
            }
        }
    }

    private fun buildViews(context: Context): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_metis)
        val status = WidgetStatusStore.read(context)

        val statusText = if (status.running) {
            val stepText = if (status.maxSteps > 0) "第 ${status.step}/${status.maxSteps} 步" else "运行中"
            "$stepText · ${status.last}"
        } else {
            status.last.ifBlank { "空闲" }
        }
        views.setTextViewText(R.id.widget_status, statusText)

        // 打开 Metis
        views.setOnClickPendingIntent(R.id.widget_open, pendingActivity(context, MainActivity::class.java, 0))
        // 新建对话
        views.setOnClickPendingIntent(
            R.id.widget_new_chat,
            pendingActivity(context, MainActivity::class.java, 1, ACTION_NEW_CHAT)
        )
        // 设置
        views.setOnClickPendingIntent(
            R.id.widget_settings,
            pendingActivity(context, MainActivity::class.java, 2, ACTION_SETTINGS)
        )
        return views
    }

    private fun pendingActivity(
        context: Context,
        cls: Class<*>,
        requestCode: Int,
        action: String? = null
    ): PendingIntent {
        val intent = Intent(context, cls).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (action != null) this.action = action
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    companion object {
        const val ACTION_UPDATE = "com.mrgreenapps.a11ypilot.widget.UPDATE"
        const val ACTION_NEW_CHAT = "com.mrgreenapps.a11ypilot.widget.NEW_CHAT"
        const val ACTION_SETTINGS = "com.mrgreenapps.a11ypilot.widget.SETTINGS"

        /** 由服务在状态变化时调用，通知小组件刷新。 */
        fun requestUpdate(context: Context) {
            context.sendBroadcast(Intent(context, MetisWidgetProvider::class.java).apply {
                action = ACTION_UPDATE
                setPackage(context.packageName)
            })
        }
    }
}
