package com.mrgreenapps.a11ypilot.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mrgreenapps.a11ypilot.MainActivity
import com.mrgreenapps.a11ypilot.R
import com.mrgreenapps.a11ypilot.EventLog
import com.mrgreenapps.a11ypilot.data.MessageStatus
import com.mrgreenapps.a11ypilot.data.Session
import com.mrgreenapps.a11ypilot.data.SessionRepository
import com.mrgreenapps.a11ypilot.data.ToolCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Keeps an agent run alive while the user uses another app or locks the screen. */
class AgentExecutionService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var sessionRepository: SessionRepository
    private var stateJob: Job? = null
    @Volatile private var currentSessionId: String = ""
    @Volatile private var currentAssistantMessageId: String = ""
    // Throttle for Running-state persistence: the coordinator emits a Running update on every
    // step (and multiple times per step), and each persistence does a full read+decode+encode+write
    // of the session's JSON message blob. Persisting at most once per 400ms avoids quadratic write
    // amplification during streaming runs.
    private var lastRunningPersistMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        sessionRepository = SessionRepository(applicationContext)
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            EventLog.append("agent-service> cancel action received")
            serviceScope.launch {
                AgentTaskCoordinator.cancel(this@AgentExecutionService)
                persistCancellation(
                    intent.getStringExtra(EXTRA_SESSION_ID).orEmpty(),
                    intent.getStringExtra(EXTRA_ASSISTANT_MESSAGE_ID).orEmpty()
                )
                postFinished("任务已停止", success = false)
                stateJob?.cancel()
                stopSelfResult(startId)
            }
            return START_NOT_STICKY
        }

        val instruction = intent?.getStringExtra(EXTRA_INSTRUCTION).orEmpty()
        val sessionJson = intent?.getStringExtra(EXTRA_SESSION).orEmpty()
        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        val assistantMessageId = intent?.getStringExtra(EXTRA_ASSISTANT_MESSAGE_ID).orEmpty()
        currentSessionId = sessionId
        currentAssistantMessageId = assistantMessageId
        startInForeground(buildRunningNotification(0, "正在准备任务"))
        EventLog.append("agent-service> physical-display PhoneUse route active")
        if (instruction.isBlank() || sessionJson.isBlank()) {
            postFinished("任务参数无效", success = false)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        val session = runCatching { Json.decodeFromString<Session>(sessionJson) }.getOrNull()
        if (session == null) {
            postFinished("无法恢复会话配置", success = false)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        // If a previous run's assistant message is still IN_PROGRESS (e.g. the user sent a new
        // message while an old run was streaming), finalize it as cancelled so it never hangs in
        // the transcript as "thinking…" forever.
        serviceScope.launch { finalizeStaleInProgress(session.id) }

        stateJob?.cancel()
        // Start the run before subscribing. This prevents a terminal StateFlow value from a
        // previous run from stopping a newly-started service before it reaches Running.
        AgentTaskCoordinator.run(this, instruction, session)
        stateJob = serviceScope.launch {
            AgentTaskCoordinator.state.collectLatest { state ->
                if (sessionId.isNotBlank() && assistantMessageId.isNotBlank()) {
                    persistState(sessionId, assistantMessageId, state)
                }
                // 同步任务状态到桌面小组件并触发刷新
                com.mrgreenapps.a11ypilot.widget.WidgetStatusStore.write(this@AgentExecutionService, state)
                com.mrgreenapps.a11ypilot.widget.MetisWidgetProvider.requestUpdate(this@AgentExecutionService)
                when (state) {
                    AgentEngine.State.Idle -> updateNotification(buildRunningNotification(0, "正在准备任务"))
                    is AgentEngine.State.Running -> updateNotification(
                        buildRunningNotification(
                            state.step,
                            if (state.last.startsWith("正在重试")) state.last
                            else applyRunningTemplate(state.last, state.step, state.maxSteps)
                        )
                    )
                    is AgentEngine.State.Done -> {
                        postFinished(if (state.success) "任务已完成" else "任务未完成", state.success)
                        delay(500)
                        stopSelfResult(startId)
                    }
                    is AgentEngine.State.Error -> {
                        postFinished(
                            if (state.message == "cancelled") "任务已停止" else "任务出错：${state.message}",
                            success = false
                        )
                        delay(500)
                        stopSelfResult(startId)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stateJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun persistState(sessionId: String, messageId: String, state: AgentEngine.State) {
        if (sessionId.isBlank() || messageId.isBlank()) return
        // Throttle intermediate Running updates; terminal states always persist immediately.
        if (state is AgentEngine.State.Running) {
            val now = System.currentTimeMillis()
            if (now - lastRunningPersistMs < 400) return
            lastRunningPersistMs = now
        }
        val message = sessionRepository.getMessages(sessionId).firstOrNull { it.id == messageId } ?: return
        val updated = when (state) {
            AgentEngine.State.Idle -> return
            is AgentEngine.State.Running -> message.copy(
                status = MessageStatus.IN_PROGRESS,
                thinkingState = state.thinkingState,
                toolCalls = state.toolCalls.ifEmpty {
                    if (state.step > 0) listOf(ToolCall(state.last.substringBefore('('), System.currentTimeMillis())) else emptyList()
                }
            )
            is AgentEngine.State.Done -> message.copy(
                content = state.summary,
                status = if (state.success) MessageStatus.COMPLETE else MessageStatus.ERROR,
                thinkingState = null,
                attachments = state.attachments.ifEmpty { null },
                toolCalls = state.toolCalls.ifEmpty { message.toolCalls.orEmpty() }
            )
            is AgentEngine.State.Error -> message.copy(
                content = if (state.message == "cancelled") "任务已停止" else "出错了：${state.message}",
                status = MessageStatus.ERROR,
                thinkingState = null,
                toolCalls = state.toolCalls.ifEmpty { message.toolCalls.orEmpty() }
            )
        }
        sessionRepository.updateMessage(updated)
    }

    private suspend fun persistCancellation(sessionId: String, messageId: String) {
        val resolvedSessionId = sessionId.ifBlank { currentSessionId }
        val resolvedMessageId = messageId.ifBlank { currentAssistantMessageId }
        if (resolvedSessionId.isBlank() || resolvedMessageId.isBlank()) return
        val message = sessionRepository.getMessages(resolvedSessionId)
            .firstOrNull { it.id == resolvedMessageId } ?: return
        if (message.status == MessageStatus.IN_PROGRESS) {
            sessionRepository.updateMessage(
                message.copy(
                    content = "任务已停止",
                    status = MessageStatus.ERROR,
                    thinkingState = null
                )
            )
        }
    }

    /** Mark any assistant message still IN_PROGRESS in a session as cancelled before a new run. */
    private suspend fun finalizeStaleInProgress(sessionId: String) {
        if (sessionId.isBlank()) return
        val stale = sessionRepository.getMessages(sessionId)
            .filter { it.role == com.mrgreenapps.a11ypilot.data.MessageRole.ASSISTANT && it.status == MessageStatus.IN_PROGRESS }
        if (stale.isEmpty()) return
        stale.forEach { message ->
            sessionRepository.updateMessage(
                message.copy(
                    content = message.content.ifBlank { "任务已取消" },
                    status = MessageStatus.ERROR,
                    thinkingState = null
                )
            )
        }
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(notification: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun postFinished(text: String, success: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.cancel(NOTIFICATION_ID)
        // 先结束前台状态再发完成通知：STOP_FOREGROUND_REMOVE 在部分 ROM 上
        // 会连带清掉刚 post 的通知，调换顺序才能保证完成提醒稳定留下。
        stopForeground(STOP_FOREGROUND_REMOVE)
        manager.notify(
            COMPLETION_NOTIFICATION_ID,
            buildCompletionNotification(text, success)
        )
    }

    private fun buildRunningNotification(step: Int, text: String): Notification {
        val title = notificationTitle()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(50, step.coerceIn(0, 50), false)
            .addAction(
                R.mipmap.ic_launcher,
                "停止",
                PendingIntent.getService(
                    this,
                    CANCEL_REQUEST_CODE,
                    Intent(this, AgentExecutionService::class.java)
                        .setAction(ACTION_CANCEL)
                        .putExtra(EXTRA_SESSION_ID, currentSessionId)
                        .putExtra(EXTRA_ASSISTANT_MESSAGE_ID, currentAssistantMessageId),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .build()
    }

    private fun buildCompletionNotification(text: String, success: Boolean): Notification {
        val title = notificationTitle()
        return NotificationCompat.Builder(this, DONE_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(if (success) "$title 已完成任务" else "$title 任务异常")
            .setContentText(text)
            .setContentIntent(openAppPendingIntent())
            .setAutoCancel(true)
            // 完成通知走高优先级渠道：会弹出（含横幅/灵动岛样式），且不再 8 秒自动消失
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            // 展开大文本，让任务结论完整显示而不是被截断成一行
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .build()
    }

    /** 读取用户自定义的通知标题，为空则用默认值。 */
    private fun notificationTitle(): String {
        val custom = runCatching {
            kotlinx.coroutines.runBlocking { AgentSettings.notificationTitle(applicationContext).first() }
        }.getOrDefault("")
        return custom.ifBlank { "Metis 后台任务" }
    }

    /** 应用用户自定义的运行中通知模板（支持 {last}/{step}/{max} 占位符），未设置则用默认拼接。 */
    private fun applyRunningTemplate(last: String, step: Int, max: Int): String {
        val template = runCatching {
            kotlinx.coroutines.runBlocking { AgentSettings.notificationRunningTemplate(applicationContext).first() }
        }.getOrDefault("")
        if (template.isBlank()) return "${last} · 第 ${step} 步"
        return template
            .replace("{last}", last)
            .replace("{step}", step.toString())
            .replace("{max}", if (max > 0) max.toString() else "?")
    }

    private fun openAppPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        OPEN_APP_REQUEST_CODE,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    companion object {
        /** 进行中任务的常驻通知（低优先级，不打扰）。 */
        private const val CHANNEL_ID = "agent_tasks"
        /** 任务完成/失败的通知（高优先级，会弹出横幅提醒）。 */
        private const val DONE_CHANNEL_ID = "agent_tasks_done"
        private const val NOTIFICATION_ID = 5101
        private const val COMPLETION_NOTIFICATION_ID = 5102
        private const val CANCEL_REQUEST_CODE = 5103
        private const val OPEN_APP_REQUEST_CODE = 5104
        private const val ACTION_CANCEL = "com.mrgreenapps.a11ypilot.action.CANCEL_AGENT"
        private const val EXTRA_INSTRUCTION = "instruction"
        private const val EXTRA_SESSION = "session"

        fun start(
            context: Context,
            instruction: String,
            session: Session,
            sessionId: String,
            assistantMessageId: String
        ) {
            val intent = Intent(context, AgentExecutionService::class.java)
                .putExtra(EXTRA_INSTRUCTION, instruction)
                .putExtra(EXTRA_SESSION, Json.encodeToString(session))
                .putExtra(EXTRA_SESSION_ID, sessionId)
                .putExtra(EXTRA_ASSISTANT_MESSAGE_ID, assistantMessageId)
            ContextCompat.startForegroundService(context, intent)
        }

        private const val EXTRA_SESSION_ID = "session_id"
        private const val EXTRA_ASSISTANT_MESSAGE_ID = "assistant_message_id"

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Metis 后台任务",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply { description = "显示后台任务进度（静默，不打扰）" }
                )
            }
            // 完成通知独立成高优先级渠道，确保任务结束时用户能真的收到提醒。
            if (manager.getNotificationChannel(DONE_CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        DONE_CHANNEL_ID,
                        "Metis 任务完成",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply { description = "任务完成或出错时弹出提醒" }
                )
            }
        }
    }
}
