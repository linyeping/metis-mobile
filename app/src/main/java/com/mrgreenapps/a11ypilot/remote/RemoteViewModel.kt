package com.mrgreenapps.a11ypilot.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 远程指挥的连接状态机。 */
enum class RemoteConnectionState {
    /** 未连接（尚未配对，或配对信息缺失）。 */
    DISCONNECTED,

    /** 配对中（正在握手/建会话）。 */
    PAIRING,

    /** 已连接（会话就绪，可发指令）。 */
    CONNECTED,

    /** 运行中（一条指令已提交，正在流式接收事件）。 */
    RUNNING
}

/** 远程指挥界面的完整 UI 状态，单一数据源。 */
data class RemoteUiState(
    val connectionState: RemoteConnectionState = RemoteConnectionState.DISCONNECTED,
    val endpoint: MetisEndpoint? = null,
    val sessionId: String? = null,
    val runId: String? = null,
    /** 本轮运行收到的事件流（含文本、工具、状态、权限）。 */
    val events: List<MetisEvent> = emptyList(),
    /** 已累积的 assistant 文本（由 TextDelta 增量拼接，用于流式渲染）。 */
    val assistantText: String = "",
    /** 等待用户审批的权限请求，同时最多一个。 */
    val pendingPermission: MetisEvent.PermissionRequest? = null,
    /** 最近一次错误，展示后可忽略。 */
    val error: String? = null
)

/**
 * 远程指挥的状态层。
 *
 * 与 [com.mrgreenapps.a11ypilot.ui.AppViewModel] 的 StateFlow 风格保持一致：所有状态通过
 * 单一 [uiState] 暴露给 Compose。事件流收集运行在 viewModelScope，随 ViewModel 清理而取消。
 */
class RemoteViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(RemoteUiState())
    val uiState: StateFlow<RemoteUiState> = _uiState.asStateFlow()

    private var client: MetisRelayClient? = null
    private var streamJob: Job? = null

    /** 用扫码/输入得到的原始配对信息建立连接。 */
    fun connect(rawInput: String) {
        val endpoint = MetisEndpoint.parse(rawInput) ?: run {
            _uiState.update { it.copy(error = "无法解析连接地址，请输入中继地址（可带配对码与 token）。") }
            return
        }
        // WebSocket 走中继时用配对码兑换，token 可选；无配对码则视为已配对直连。
        if (endpoint.pairingCode.isNullOrBlank() && endpoint.token.isNullOrBlank()) {
            _uiState.update { it.copy(error = "缺少配对码或 token，请确认扫码/配对信息完整。") }
            return
        }
        disconnect() // 重连前清理旧连接与流
        viewModelScope.launch {
            _uiState.update { it.copy(connectionState = RemoteConnectionState.PAIRING, error = null) }
            try {
                val c = MetisRelayClient.from(endpoint)
                c.hello() // 握手：连接 WebSocket + 配对 + hello
                val session = c.createSession()
                client = c
                _uiState.update {
                    it.copy(
                        connectionState = RemoteConnectionState.CONNECTED,
                        endpoint = endpoint,
                        sessionId = session.id,
                        error = null
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                client = null
                _uiState.update {
                    it.copy(
                        connectionState = RemoteConnectionState.DISCONNECTED,
                        error = t.message ?: "连接失败"
                    )
                }
            }
        }
    }

    /** 提交一条指令给桌面端，并开始流式收集事件。 */
    fun sendCommand(prompt: String) {
        val c = client ?: return
        val sessionId = _uiState.value.sessionId ?: return
        if (prompt.isBlank()) return
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    connectionState = RemoteConnectionState.RUNNING,
                    events = emptyList(),
                    assistantText = "",
                    pendingPermission = null,
                    error = null
                )
            }
            try {
                val run = c.submitRun(sessionId, prompt)
                _uiState.update { it.copy(runId = run.id) }
                c.streamEvents(run.id).collect { event ->
                    _uiState.update { state -> state.applyEvent(event) }
                }
                // 流正常结束（桌面端关闭连接）。
                _uiState.update { it.copy(connectionState = RemoteConnectionState.CONNECTED, runId = null) }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(
                        connectionState = RemoteConnectionState.CONNECTED,
                        runId = null,
                        error = t.message ?: "运行失败"
                    )
                }
            }
        }
    }

    /** 审批当前待处理的权限请求。 */
    fun answerPermission(allow: Boolean) {
        val request = _uiState.value.pendingPermission ?: return
        val c = client ?: return
        viewModelScope.launch {
            try {
                c.answerPermission(request.requestId, allow)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _uiState.update { it.copy(error = t.message ?: "回传权限决策失败") }
            } finally {
                _uiState.update { it.copy(pendingPermission = null) }
            }
        }
    }

    /** 取消当前运行。 */
    fun cancelRun() {
        val runId = _uiState.value.runId ?: return
        val c = client ?: return
        viewModelScope.launch {
            try {
                c.cancelRun(runId)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _uiState.update { it.copy(error = t.message ?: "取消失败") }
            } finally {
                streamJob?.cancel()
                _uiState.update { it.copy(connectionState = RemoteConnectionState.CONNECTED, runId = null) }
            }
        }
    }

    /** 断开连接并清空状态。 */
    fun disconnect() {
        streamJob?.cancel()
        streamJob = null
        client = null
        _uiState.value = RemoteUiState()
    }

    /** 把一条事件折叠进当前状态：记录事件、拼文本、记权限、收敛运行态。 */
    private fun RemoteUiState.applyEvent(event: MetisEvent): RemoteUiState {
        val withEvent = copy(events = events + event)
        return when (event) {
            is MetisEvent.TextDelta -> withEvent.copy(assistantText = assistantText + event.text)
            is MetisEvent.PermissionRequest -> withEvent.copy(pendingPermission = event)
            is MetisEvent.Status -> withEvent.copy(
                connectionState = if (event.isTerminal) RemoteConnectionState.CONNECTED else connectionState,
                runId = if (event.isTerminal) null else runId,
                error = if (event.isTerminal && event.isError) (event.detail.ifBlank { error }) else error
            )
            // ToolCall 与 Unknown 仅记录到 events 列表供 UI 标记，不改变其它字段。
            is MetisEvent.ToolCall -> withEvent
            is MetisEvent.Unknown -> withEvent
        }
    }

    override fun onCleared() {
        streamJob?.cancel()
        super.onCleared()
    }
}
