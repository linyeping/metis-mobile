package com.mrgreenapps.a11ypilot.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Probes an OpenAI-compatible relay and returns the model IDs it actually exposes. */
object OpenAIModelProbe {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build()

    suspend fun probe(baseUrl: String, apiKey: String): List<String> = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "请先填写 GPT API Key" }
        val root = baseUrl.trim().trimEnd('/').removeSuffix("/v1")
        var body: String? = null
        val failures = mutableListOf<String>()
        for (url in listOf("$root/v1/models", "$root/models")) {
            val result = runCatching {
                ApiRequestExecutor.execute(client, Request.Builder().url(url)
                    .header("Authorization", "Bearer $apiKey")
                    .header("Accept", "application/json").get().build(), "GPT 模型探针")
            }.onFailure { error -> failures += "$url：${ApiErrorMessage.fromThrowable(error)}" }.getOrNull()
            if (result != null) { body = result; break }
        }
        val responseBody = body ?: throw ApiCallException(
            "GPT 模型探针未成功：${failures.joinToString("；").take(600)}",
            retryable = true
        )
        json.parseToJsonElement(responseBody).jsonObject["data"]?.jsonArray
            ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
            ?.map(String::trim)?.filter(String::isNotBlank)?.distinct()?.sorted()
            .orEmpty().also { require(it.isNotEmpty()) { "服务端未返回可用 GPT 模型" } }
    }
}
