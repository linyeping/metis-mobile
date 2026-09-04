package com.mrgreenapps.a11ypilot.phoneuse

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.mrgreenapps.a11ypilot.agent.AgentExecutionService
import com.mrgreenapps.a11ypilot.agent.AutomationScheduler
import com.mrgreenapps.a11ypilot.data.AutomationRepository
import com.mrgreenapps.a11ypilot.data.AutomationTask
import com.mrgreenapps.a11ypilot.data.AutomationTrigger
import com.mrgreenapps.a11ypilot.data.Message
import com.mrgreenapps.a11ypilot.data.MessageRole
import com.mrgreenapps.a11ypilot.data.MessageStatus
import com.mrgreenapps.a11ypilot.data.Session
import com.mrgreenapps.a11ypilot.data.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

/** Receives alarm deliveries and starts the same foreground agent path used by the UI. */
class AutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_RUN_TASK -> runTask(appContext, intent.getStringExtra(EXTRA_TASK_ID).orEmpty())
                    Intent.ACTION_BOOT_COMPLETED,
                    Intent.ACTION_TIME_CHANGED,
                    Intent.ACTION_TIMEZONE_CHANGED -> AutomationScheduler.rescheduleAll(appContext)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun runTask(context: Context, taskId: String) {
        if (taskId.isBlank()) return
        val repository = AutomationRepository(context)
        val stored = repository.getTask(taskId) ?: return
        if (!stored.enabled) return

        val now = System.currentTimeMillis()
        val nextTask = if (stored.trigger == AutomationTrigger.ONCE) {
            stored.copy(enabled = false, nextRunAt = 0L, lastRunAt = now)
        } else {
            stored.copy(lastRunAt = now)
        }
        val scheduledTask = if (stored.trigger != AutomationTrigger.ONCE) {
            AutomationScheduler.schedule(context, nextTask)
        } else {
            repository.upsert(nextTask)
            nextTask
        }

        val sessionRepository = SessionRepository(context)
        val session = stored.sessionId
            ?.let { sessionRepository.getSession(it) }
            ?: sessionRepository.createSession(
                mode = stored.mode,
                provider = stored.provider,
                model = stored.model,
                reasoningIntensity = stored.reasoningIntensity,
                safetyLevel = stored.safetyLevel,
                title = "自动化 · ${stored.name}",
                makeActive = false
            ).also { created -> repository.upsert(scheduledTask.copy(sessionId = created.id)) }

        val user = Message(
            id = UUID.randomUUID().toString(),
            sessionId = session.id,
            role = MessageRole.USER,
            content = stored.prompt,
            timestamp = now
        )
        val assistant = Message(
            id = UUID.randomUUID().toString(),
            sessionId = session.id,
            role = MessageRole.ASSISTANT,
            content = "",
            timestamp = now,
            status = MessageStatus.IN_PROGRESS,
            thinkingState = stored.mode.defaultThinkingStateForAutomation()
        )
        sessionRepository.addMessage(user)
        sessionRepository.addMessage(assistant)
        AgentExecutionService.start(context, stored.prompt, session, session.id, assistant.id)
    }

    companion object {
        const val ACTION_RUN_TASK = "com.mrgreenapps.a11ypilot.action.RUN_AUTOMATION"
        const val EXTRA_TASK_ID = "automation_task_id"
    }
}

private fun com.mrgreenapps.a11ypilot.data.WorkMode.defaultThinkingStateForAutomation() =
    when (this) {
        com.mrgreenapps.a11ypilot.data.WorkMode.CHAT -> com.mrgreenapps.a11ypilot.data.ThinkingState.UNDERSTANDING
        com.mrgreenapps.a11ypilot.data.WorkMode.COWORK -> com.mrgreenapps.a11ypilot.data.ThinkingState.WORKING
        com.mrgreenapps.a11ypilot.data.WorkMode.CODE -> com.mrgreenapps.a11ypilot.data.ThinkingState.EXECUTING
    }
