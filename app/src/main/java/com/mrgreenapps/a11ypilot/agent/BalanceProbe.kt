package com.mrgreenapps.a11ypilot.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.math.BigDecimal
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

data class BalanceResult(
    val amount: String,
    val currency: String
)

/** Queries provider-native balance endpoints without converting their currencies. */
object BalanceProbe {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun probeRelay(baseUrl: String, apiKey: String): BalanceResult = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "请先填写 API 中转 Key" }
        val root = baseUrl.trim().trimEnd('/').removeSuffix("/v1")
        var lastError: Throwable? = null
        // Try the OpenAI-style /v1/usage first, fall back to common alternates. The
        // endpoint shape depends on the user's chosen relay, so we keep the path list
        // generic — operators expose one of these.
        for (path in listOf("/v1/usage", "/usage", "/v1/balance", "/balance")) {
            val request = Request.Builder()
                .url(root + path)
                .header("Authorization", "Bearer $apiKey")
                .header("X-API-Key", apiKey)
                .header("Accept", "application/json")
                .header("Cache-Control", "no-cache")
                .header("Pragma", "no-cache")
                .get()
                .build()
            val result = runCatching { executeWithBackoff(request, "API 中转余额接口 ($path)") }
            result.getOrNull()?.let { response -> return@withContext parseRelay(response) }
            val error = result.exceptionOrNull()
            // A valid route with an invalid/unauthorized key must not be hidden by a
            // later fallback route's 404 response.
            if (error is ApiCallException && error.statusCode in setOf(401, 403)) throw error
            lastError = error
        }
        throw (lastError ?: ApiCallException("API 中转余额接口未响应"))
    }

    /** VPN/proxy routes can take a few seconds to become usable after a network switch. */
    private suspend fun executeWithBackoff(request: Request, service: String): String {
        var last: Throwable? = null
        repeat(3) { attempt ->
            try {
                return ApiRequestExecutor.execute(client, request, service)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                last = error
                val retryable = when (error) {
                    is ApiCallException -> error.retryable && error.statusCode !in setOf(401, 403)
                    is IOException, is SocketTimeoutException -> true
                    else -> false
                }
                if (!retryable || attempt == 2) throw error
                delay(700L * (attempt + 1))
            }
        }
        throw last ?: ApiCallException("API 中转余额接口未响应")
    }

    suspend fun probeDeepSeek(apiKey: String): BalanceResult = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "请先填写 DeepSeek API Key" }
        val request = Request.Builder()
            .url("https://api.deepseek.com/user/balance")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .get()
            .build()
        parseDeepSeek(executeWithBackoff(request, "DeepSeek 余额接口"))
    }

    /** Backward-compatible test/API alias for older callers. */
    @Deprecated("Use parseRelay")
    internal fun parsePinAi(responseText: String): BalanceResult = parseRelay(responseText)

    internal fun parseRelay(responseText: String): BalanceResult {
        val root = json.parseToJsonElement(responseText).jsonObject
        val payload = root["data"] as? JsonObject ?: root
        val amount = payload.decimal("balance")
            ?: payload.decimal("remaining")
            ?: payload.decimal("available_balance")
            ?: payload.decimal("balance_amount")
            ?: (payload["balance"] as? JsonObject)?.decimal("amount")
            ?: (payload["balance"] as? JsonObject)?.decimal("value")
            ?: throw ApiCallException("API 中转余额接口未返回余额字段")
        val currency = (payload.string("currency")
            ?: payload.string("unit")
            ?: (payload["balance"] as? JsonObject)?.string("currency")
            ?: (payload["balance"] as? JsonObject)?.string("unit")
            ?: root.string("currency")
            ?: root.string("unit")
            ?: "USD").uppercase()
        return BalanceResult(amount.normalized(), currency)
    }

    internal fun parseDeepSeek(responseText: String): BalanceResult {
        val root = json.parseToJsonElement(responseText).jsonObject
        val balances = root["balance_infos"]?.jsonArray.orEmpty()
        val selected = balances.mapNotNull { it as? JsonObject }
            .firstOrNull { it.string("currency")?.equals("CNY", ignoreCase = true) == true }
            ?: balances.firstOrNull()?.jsonObject
            ?: throw ApiCallException("DeepSeek 余额接口未返回 balance_infos")
        val amount = selected.decimal("total_balance")
            ?: throw ApiCallException("DeepSeek 余额接口未返回 total_balance")
        val currency = selected.string("currency")?.uppercase().orEmpty().ifBlank { "CNY" }
        return BalanceResult(amount.normalized(), currency)
    }

    private fun JsonObject.string(name: String): String? =
        this[name]?.let { value ->
            runCatching { value.jsonPrimitive.contentOrNull?.trim() }
                .getOrNull()
                ?.takeIf(String::isNotBlank)
        }

    private fun JsonObject.decimal(name: String): BigDecimal? {
        val value: JsonElement = this[name] ?: return null
        val primitive = runCatching { value.jsonPrimitive }.getOrNull() ?: return null
        return primitive.contentOrNull?.toBigDecimalOrNull()
            ?: primitive.doubleOrNull?.let(BigDecimal::valueOf)
    }

    private fun BigDecimal.normalized(): String = stripTrailingZeros().toPlainString()
}
