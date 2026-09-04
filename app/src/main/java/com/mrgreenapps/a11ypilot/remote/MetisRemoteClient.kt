package com.mrgreenapps.a11ypilot.remote

import com.mrgreenapps.a11ypilot.agent.ApiRequestExecutor
import com.mrgreenapps.a11ypilot.agent.HttpClients
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * `metis.cli_attach.v1` 的手机端客户端。
 *
 * 复用项目既有的两个基础设施：
 *  - [HttpClients.shared]：进程级共享 OkHttpClient（连接池/线程池复用）。
 *  - [ApiRequestExecutor]：JSON 请求的重试 + 取消，以及 SSE `data:` 行解析。
 *
 * 鉴权统一走请求头 `X-Metis-CLI-Token`。所有 attach 请求实际发往中继地址，中继再转发到桌面端。
 */
class MetisRemoteClient(
    private val baseUrl: String,
    private val token: String,
    private val http: OkHttpClient = HttpClients.shared
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val base = baseUrl.trimEnd('/')

    /** 握手：确认中继可达、token 有效。返回服务端问候文本。 */
    suspend fun hello(): String {
        val request = auth(Request.Builder().url("$base/api/cli/v1/hello").get()).build()
        val body = ApiRequestExecutor.execute(http, request, "Metis CLI")
        return runCatching {
            json.parseToJsonElement(body).jsonObject["message"]?.jsonPrimitive?.contentOrNull
        }.getOrNull() ?: body
    }

    /** 创建（或续接）会话，返回会话 id。 */
    suspend fun createSession(): MetisSession {
        val request = auth(
            Request.Builder()
                .url("$base/api/cli/v1/sessions")
                .post("{}".toRequestBody(JSON_MEDIA))
        ).build()
        val body = ApiRequestExecutor.execute(http, request, "Metis CLI")
        val root = json.parseToJsonElement(body).jsonObject
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
        val request = auth(
            Request.Builder()
                .url("$base/api/cli/v1/runs")
                .post(json.encodeToString(JsonObject.serializer(), payload).toRequestBody(JSON_MEDIA))
        ).build()
        val body = ApiRequestExecutor.execute(http, request, "Metis CLI")
        val root = json.parseToJsonElement(body).jsonObject
        return MetisRun(
            id = root["id"]?.jsonPrimitive?.contentOrNull
                ?: root["run_id"]?.jsonPrimitive?.contentOrNull
                ?: throw IllegalStateException("run 响应缺少 id"),
            status = root["status"]?.jsonPrimitive?.contentOrNull
        )
    }

    /**
     * 以 SSE 流式接收事件。复用 [ApiRequestExecutor.executeStream]，它对非 2xx 会抛
     * [com.mrgreenapps.a11ypilot.agent.ApiCallException]，对取消会正确中断底层 OkHttp 调用。
     */
    fun streamEvents(runId: String): Flow<MetisEvent> = flow {
        val request = auth(
            Request.Builder()
                .url("$base/api/cli/v1/runs/$runId/events")
                .header("Accept", "text/event-stream")
                .get()
        ).build()
        ApiRequestExecutor.executeStream(http, request).collect { data ->
            MetisEventParser.parse(data)?.let { emit(it) }
        }
    }

    /** 取消一次运行。 */
    suspend fun cancelRun(runId: String) {
        val request = auth(
            Request.Builder()
                .url("$base/api/cli/v1/runs/$runId/cancel")
                .post(ByteArray(0).toRequestBody(null))
        ).build()
        ApiRequestExecutor.execute(http, request, "Metis CLI")
    }

    /**
     * 回传权限决策。
     *
     * 桌面端真实端点：`POST /permissions/requests/<request_id>/answer`（不在 /api/cli/v1/ 前缀下）。
     * 请求体字段为 approved/choice/remember/grant；最简用法 choice="once" 即本次允许/拒绝。
     */
    suspend fun answerPermission(requestId: String, allow: Boolean) {
        val payload = buildJsonObject {
            put("approved", allow)
            put("choice", "once")
            put("remember", "")
            put("grant", "")
        }
        val request = auth(
            Request.Builder()
                .url("$base/permissions/requests/$requestId/answer")
                .post(json.encodeToString(JsonObject.serializer(), payload).toRequestBody(JSON_MEDIA))
        ).build()
        ApiRequestExecutor.execute(http, request, "Metis CLI")
    }

    /** 给请求附加鉴权头与 JSON 内容类型。 */
    private fun auth(builder: Request.Builder): Request.Builder = builder
        .header("X-Metis-CLI-Token", token)
        .header("Content-Type", "application/json")

    private companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
