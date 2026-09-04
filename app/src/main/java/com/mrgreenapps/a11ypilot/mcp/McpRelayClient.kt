package com.mrgreenapps.a11ypilot.mcp

import com.mrgreenapps.a11ypilot.EventLog
import com.mrgreenapps.a11ypilot.agent.ToolExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Optional outbound relay client that lets the phone's MCP server be reached from outside the LAN
 * without exposing 0.0.0.0 to the public internet.
 *
 * The phone polls a user-configured relay endpoint (long-polling) for pending JSON-RPC requests,
 * dispatches them through the same [ToolExecutor], and POSTs the responses back. The relay is a
 * thin request/response forwarder; the phone never accepts inbound connections.
 *
 * Relay protocol (configured [relayBaseUrl]):
 *   GET  {relayBaseUrl}/pull?token={relayToken}     → {"request": {...}} or {"request": null}
 *   POST {relayBaseUrl}/push?token={relayToken}     body = { "id": "<request id>", "response": {...} }
 */
class McpRelayClient(
    private val relayBaseUrl: String,
    private val relayToken: String,
    private val rpc: JsonRpc
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile
    var status: String = "Stopped"
        private set

    fun start() {
        if (job != null) return
        val base = relayBaseUrl.trimEnd('/')
        if (base.isBlank()) return
        status = "Connecting"
        job = scope.launch {
            EventLog.append("relay> polling $base")
            while (isActive) {
                try {
                    pollOnce(base)
                } catch (t: Throwable) {
                    EventLog.append("relay> poll error: ${t.javaClass.simpleName}: ${t.message}")
                    status = "Reconnecting"
                    delay(3000)
                }
                delay(500)
            }
        }
    }

    private suspend fun pollOnce(base: String) {
        val pullReq = Request.Builder()
            .url("$base/pull?token=$relayToken")
            .get()
            .build()
        val body = http.newCall(pullReq).execute().use { resp ->
            if (!resp.isSuccessful) {
                status = "Relay HTTP ${resp.code}"
                delay(3000)
                return
            }
            resp.body?.string().orEmpty()
        }
        if (body.isBlank()) return
        val request = runCatching { json.parseToJsonElement(body).jsonObject["request"]?.jsonObject }
            .getOrNull() ?: return
        val requestId = request["id"]?.let { json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), it) } ?: ""
        EventLog.append("relay> dispatching request")
        val response = rpc.handle(request)

        val pushBody = json.encodeToString(JsonObject.serializer(), kotlinx.serialization.json.buildJsonObject {
            put("id", kotlinx.serialization.json.JsonPrimitive(requestId))
            put("response", response)
        })
        val pushReq = Request.Builder()
            .url("$base/push?token=$relayToken")
            .post(pushBody.toRequestBody(JSON_MEDIA))
            .build()
        http.newCall(pushReq).execute().use { it.body?.string() }
        status = "Connected"
    }

    fun stop() {
        job?.cancel()
        job = null
        status = "Stopped"
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
