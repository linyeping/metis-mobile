package com.mrgreenapps.a11ypilot.agent

import android.content.Context
import com.mrgreenapps.a11ypilot.data.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process-wide owner for the agent run. The foreground service and the Compose UI
 * must observe the same engine so leaving Metis cannot cancel or duplicate a task.
 */
object AgentTaskCoordinator {
    private val lock = Any()
    private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<AgentEngine.State>(AgentEngine.State.Idle)
    private val _usage = MutableStateFlow(AgentEngine.Usage())
    private val _pendingApproval = MutableStateFlow<AgentEngine.PendingApproval?>(null)

    private var engine: AgentEngine? = null
    private var stateBridge: Job? = null
    private var usageBridge: Job? = null
    private var approvalBridge: Job? = null

    val state: StateFlow<AgentEngine.State> = _state.asStateFlow()
    val usage: StateFlow<AgentEngine.Usage> = _usage.asStateFlow()
    val pendingApproval: StateFlow<AgentEngine.PendingApproval?> = _pendingApproval.asStateFlow()

    private fun engine(context: Context): AgentEngine = synchronized(lock) {
        engine ?: AgentEngine(context.applicationContext).also { created ->
            engine = created
            stateBridge = bridgeScope.launch {
                created.state.collect { _state.value = it }
            }
            usageBridge = bridgeScope.launch {
                created.usage.collect { _usage.value = it }
            }
            approvalBridge = bridgeScope.launch {
                created.pendingApproval.collect { _pendingApproval.value = it }
            }
        }
    }

    fun run(context: Context, instruction: String, session: Session) {
        engine(context).run(instruction, session)
    }

    /**
     * 重新生成某一条群组成员消息（仅该成员重新跑 LLM，不影响其它成员已发内容）。
     * 见 [AgentEngine.regenerateGroupMember]。
     */
    fun regenerateGroupMember(context: Context, sessionId: String, messageId: String): Boolean {
        return engine(context).regenerateGroupMember(sessionId, messageId)
    }

    fun cancel(context: Context) {
        engine(context).cancel()
    }

    fun respondApproval(context: Context, approved: Boolean) {
        engine(context).respondApproval(approved)
    }
}
