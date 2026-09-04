package com.mrgreenapps.a11ypilot.remote

import com.mrgreenapps.a11ypilot.agent.HttpClients
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * 手机端 → 中继服务器的 WebSocket 传输层（`metis.cli_attach.relay.v1`）。
 *
 * 中继协议与桌面端 `relay_client.py` 对齐，手机端通过 `/ws/phone` 接入：
 *
 *   - 先发 `pair`（携带 pairing_code）一次性兑换配对，中继回 `paired`（含 pairing_token）。
 *   - 之后用 `command` 消息封装 attach 操作（op: hello/create_session/create_run/stream_events/cancel），
 *     每个 command 带全局唯一的 command_id，中继回 `ack` 表示已入队，桌面端执行后回 `result`/`event`。
 *   - 本类把 `command → result/event` 的异步回包，重新封装成与 [MetisRemoteClient] 相同的业务接口，
 *     使上层 [RemoteViewModel] 无需改动即可切换传输方式。
 *
 * 复用 [HttpClients.shared]（OkHttp 原生支持 WebSocket，无需额外依赖）。
 */
class MetisRelayClient(
    private val baseUrl: String,
    private val pairingCode: String?,
    private val http: OkHttpClient = HttpClients.shared
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val base = baseUrl.trimEnd('/')

    private val wsUrl = base
        .replaceFirst("https://", "wss://")
        .replaceFirst("http://", "ws://") + "/ws/phone"

    private val commandSeq = AtomicLong(0)

    /** command_id -> 待决结果。非流式命令用 [PendingCommand.deferred]，流式命令用 [PendingCommand.events]。 */
    private val pending = ConcurrentHashMap<String, PendingCommand>()

    private var ws: WebSocket? = null

    private class PendingCommand(
        val deferred: CompletableDeferred<JsonObject> = CompletableDeferred(),
        val events: Channel<JsonObject> = Channel(Channel.UNLIMITED)
    )

    // ------------------------------------------------------------------
    // 业务接口（与 MetisRemoteClient 对齐）
    // ------------------------------------------------------------------

    /** 握手：连接 WebSocket + 配对 + hello，返回服务端问候文本。 */
    suspend fun hello(): String {
        ensureConnected()
        val hello = command("hello", buildJsonObject { }).deferred.await()
        return hello["message"]?.jsonPrimitive?.contentOrNull ?: "connected"
    }

    /** 创建（或续接）会话，返回会话 id。 */
    suspend fun createSession(): MetisSession {
        val root = command("create_session", buildJsonObject { }).deferred.await()
        return MetisSession(
            id = root["id"]?.jsonPrimitive?.contentOrNull
                ?: root["session_id"]?.jsonPrimitive?.contentOrNull
                ?: throw IllegalStateException("会话响应缺少 id")
        )
    }

    /** 提交任务，创建一次 run。 */
    suspend fun submitRun(sessionId: String, prompt: String): MetisRun {
        val payload = buildJsonObject {
            put("session_id", sessionId)
            put("prompt", prompt)
        }
        val root = command("create_run", payload).deferred.await()
        return MetisRun(
            id = root["id"]?.jsonPrimitive?.contentOrNull
                ?: root["run_id"]?.jsonPrimitive?.contentOrNull
                ?: throw IllegalStateException("run 响应缺少 id"),
            status = root["status"]?.jsonPrimitive?.contentOrNull
        )
    }

    /** 以流式接收事件（中继把桌面端的 SSE 事件逐条转成 event 消息回传）。 */
    fun streamEvents(runId: String): Flow<MetisEvent> = flow {
        val cmdId = nextCommandId()
        val pc = PendingCommand()
        pending[cmdId] = pc
        try {
            sendCommand(cmdId, "stream_events", buildJsonObject { put("run_id", runId) })
            for (eventJson in pc.events) {
                MetisEventParser.parseFromJson(eventJson)?.let { emit(it) }
            }
        } finally {
            pending.remove(cmdId)
            pc.events.close()
        }
    }

    /** 取消一次运行。 */
    suspend fun cancelRun(runId: String) {
        command("cancel", buildJsonObject { put("run_id", runId) }).deferred.await()
    }

    /** 回传权限决策（经中继转发到桌面端）。字段对齐桌面端 /permissions/requests/<id>/answer 的解析。 */
    suspend fun answerPermission(requestId: String, allow: Boolean) {
        val payload = buildJsonObject {
            put("request_id", requestId)
            put("approved", allow)
            put("choice", "once")
            put("remember", "")
            put("grant", "")
        }
        command("permission_answer", payload).deferred.await()
    }

    /** 断开 WebSocket。 */
    fun disconnect() {
        ws?.close(1000, "bye")
        ws = null
        pending.clear()
    }

    // ------------------------------------------------------------------
    // 连接与消息分发
    // ------------------------------------------------------------------

    private fun nextCommandId(): String = "c${commandSeq.incrementAndGet()}"

    /** 建立连接（幂等）：open 成功后若带配对码则先 pair 兑换，再返回。 */
    private suspend fun ensureConnected() {
        if (ws != null) return
        val socket = suspendCancellableCoroutine<WebSocket> { cont ->
            val request = Request.Builder().url(wsUrl).build()
            val listener = Listener(cont)
            val w = http.newWebSocket(request, listener)
            cont.invokeOnCancellation { w.cancel() }
        }
        ws = socket

        // 配对（若有配对码）。
        if (!pairingCode.isNullOrBlank()) {
            val paired = CompletableDeferred<JsonObject>()
            pending["__pair__"] = PendingCommand(deferred = paired)
            try {
                sendJson(buildJsonObject {
                    put("type", "pair")
                    put("pairing_code", pairingCode)
                })
                paired.await() // 中继回 paired 时完成
            } finally {
                pending.remove("__pair__")
            }
        }
    }

    /**
     * 统一的消息分发 listener。open/failure 通过 [openContinuation] 回传；
     * 消息按 type 分发到 pending 中对应 command_id 的 deferred / channel。
     */
    private inner class Listener(
        private val openContinuation: kotlinx.coroutines.CancellableContinuation<WebSocket>
    ) : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (openContinuation.isActive) openContinuation.resume(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            val type = obj["type"]?.jsonPrimitive?.contentOrNull
            when (type) {
                "paired" -> pending["__pair__"]?.deferred?.complete(obj)
                "result" -> handleResult(obj)
                "event" -> handleEvent(obj)
                "error" -> handleError(obj)
                "ack", "pong" -> Unit
                else -> Unit
            }
        }

        private fun handleResult(obj: JsonObject) {
            val cmdId = obj["command_id"]?.jsonPrimitive?.contentOrNull ?: return
            val pc = pending[cmdId] ?: return
            val ok = obj["ok"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
            if (ok) {
                val data = obj["data"]?.jsonObject ?: buildJsonObject { }
                pc.deferred.complete(data)
            } else {
                val err = obj["error"]?.jsonPrimitive?.contentOrNull ?: "命令执行失败"
                pc.deferred.completeExceptionally(IllegalStateException(err))
            }
        }

        private fun handleEvent(obj: JsonObject) {
            val cmdId = obj["command_id"]?.jsonPrimitive?.contentOrNull ?: return
            val pc = pending[cmdId] ?: return
            val event = obj["event"]?.jsonObject ?: return
            val complete = event["complete"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
            if (complete == true) {
                pc.events.close()
            } else {
                pc.events.trySend(event)
            }
        }

        private fun handleError(obj: JsonObject) {
            val msg = obj["message"]?.jsonPrimitive?.contentOrNull ?: "中继错误"
            val cmdId = obj["command_id"]?.jsonPrimitive?.contentOrNull
            if (cmdId != null) {
                pending[cmdId]?.deferred?.completeExceptionally(IllegalStateException(msg))
            } else {
                pending["__pair__"]?.deferred?.completeExceptionally(IllegalStateException(msg))
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (openContinuation.isActive) openContinuation.resumeWith(Result.failure(t))
            pending.values.forEach { pc ->
                pc.deferred.completeExceptionally(t)
                pc.events.close()
            }
            pending.clear()
        }
    }

    private fun sendJson(obj: JsonObject) {
        ws?.send(json.encodeToString(JsonObject.serializer(), obj))
    }

    private fun sendCommand(commandId: String, op: String, payload: JsonObject) {
        sendJson(buildJsonObject {
            put("type", "command")
            put("command_id", commandId)
            put("op", op)
            put("payload", payload)
        })
    }

    private fun command(op: String, payload: JsonObject): PendingCommand {
        val cmdId = nextCommandId()
        val pc = PendingCommand()
        pending[cmdId] = pc
        sendCommand(cmdId, op, payload)
        return pc
    }

    companion object {
        /** 从配对信息构造。 */
        fun from(endpoint: MetisEndpoint): MetisRelayClient =
            MetisRelayClient(endpoint.baseUrl, endpoint.pairingCode)
    }
}
