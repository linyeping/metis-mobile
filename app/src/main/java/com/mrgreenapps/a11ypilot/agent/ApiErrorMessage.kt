package com.mrgreenapps.a11ypilot.agent

import com.mrgreenapps.a11ypilot.EventLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A request failure with enough metadata for the key router to make a retry decision. */
class ApiCallException(
    message: String,
    cause: Throwable? = null,
    val statusCode: Int? = null,
    val retryable: Boolean = false
) : RuntimeException(message, cause)

object ApiErrorMessage {
    fun fromHttp(service: String, code: Int, body: String): String {
        val detail = extractDetail(body)
        return when (code) {
            401 -> withDetail(
                "$service 未通过身份验证（HTTP 401），API Key 缺失、无效或未被当前提供商接受",
                detail
            )
            403 -> withDetail(
                "$service 已响应但拒绝请求（HTTP 403），请检查余额、Key 有效期、模型权限、分组和出口地区",
                detail
            )
            404 -> withDetail(
                "$service 未找到请求路由（HTTP 404），请检查 Base URL、Responses API 路径和 OpenAI 分组",
                detail
            )
            429 -> "$service 请求过于频繁（HTTP 429），请稍后重试或切换模型。"
            502, 503, 504 -> "$service 上游暂时不可用（HTTP $code）。已重试一次仍失败，请稍后重试或切换模型/提供商。"
            else -> buildString {
                append("$service 请求失败（HTTP $code）")
                if (detail.isNotBlank()) append("：").append(detail)
                append('。')
            }
        }
    }

    fun fromThrowable(error: Throwable): String = when (error) {
        is ApiCallException -> error.message.orEmpty()
        is SocketTimeoutException -> "服务器在限定时间内没有返回结果，请检查当前模型、代理链路和中转站使用记录后重试。"
        is IOException -> "连接在响应完成前中断。请在“设置 > 网络诊断”检查 VPN 分应用代理、系统代理和中转站连通性。"
        else -> error.message?.takeIf { it.isNotBlank() } ?: "请求失败，请检查网络和模型设置。"
    }

    private fun withDetail(summary: String, detail: String): String = buildString {
        append(summary)
        if (detail.isNotBlank()) append("。服务端：").append(detail)
        append('。')
    }

    private fun extractDetail(body: String): String = runCatching {
        val root = Json.parseToJsonElement(body).jsonObject
        val error = root["error"]?.jsonObject
        (error?.get("message") ?: root["message"])?.jsonPrimitive?.contentOrNull.orEmpty()
    }.getOrDefault("").replace(Regex("\\s+"), " ").take(180)
}

object ApiRequestExecutor {
    private val transientCodes = setOf(502, 503, 504)

    suspend fun execute(client: OkHttpClient, request: Request, service: String): String {
        var lastError: Throwable? = null
        for (attempt in 0..1) {
            try {
                val response = executeCancellable(client, request)
                response.use { result ->
                    val body = result.body?.string().orEmpty()
                    if (result.isSuccessful) return body
                    if (result.code !in transientCodes || attempt == 1) {
                        throw ApiCallException(
                            message = ApiErrorMessage.fromHttp(service, result.code, body),
                            statusCode = result.code,
                            retryable = result.code in setOf(401, 403, 429, 500, 502, 503, 504)
                        )
                    }
                    lastError = ApiCallException(
                        message = ApiErrorMessage.fromHttp(service, result.code, body),
                        statusCode = result.code,
                        retryable = true
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IOException) {
                lastError = error
                EventLog.append("http> $service network=${error.javaClass.simpleName}: ${(error.message ?: "connection failed").take(180)}")
                if (error is SocketTimeoutException || attempt == 1) {
                    throw ApiCallException(ApiErrorMessage.fromThrowable(error), error, retryable = true)
                }
            }
            delay(450)
        }
        throw ApiCallException(ApiErrorMessage.fromThrowable(lastError ?: IOException("connection closed")), lastError)
    }

    /** Uses OkHttp's async cancellation hook so stopping a run interrupts an in-flight request. */
    private suspend fun executeCancellable(client: OkHttpClient, request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) continuation.resume(response)
                    else response.close()
                }
            })
        }

    /**
     * Executes a request and emits each SSE `data:` payload line as it arrives, so a caller can
     * stream an LLM response incrementally. Cancellation closes the underlying call.
     */
    fun executeStream(client: OkHttpClient, request: Request): kotlinx.coroutines.flow.Flow<String> =
        kotlinx.coroutines.flow.flow {
            val call = client.newCall(request)
            try {
                val response = call.execute()
                response.use { result ->
                    if (!result.isSuccessful) {
                        val body = result.body?.string().orEmpty()
                        throw ApiCallException(
                            message = ApiErrorMessage.fromHttp("流式 API", result.code, body),
                            statusCode = result.code,
                            retryable = result.code in setOf(401, 403, 429, 500, 502, 503, 504)
                        )
                    }
                    val source = result.body?.source() ?: return@use
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: continue
                        if (line.startsWith("data:")) {
                            val data = line.removePrefix("data:").trim()
                            if (data.isNotEmpty() && data != "[DONE]") emit(data)
                        }
                    }
                }
            } finally {
                call.cancel()
            }
        }.flowOn(Dispatchers.IO)
}
