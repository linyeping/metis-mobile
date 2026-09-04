package com.mrgreenapps.a11ypilot.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.mrgreenapps.a11ypilot.data.ModelCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Reads the provider's live model catalog instead of trusting stale aliases. */
object DeepSeekModelProbe {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    suspend fun probe(apiKey: String): List<String> = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "请先填写 DeepSeek API Key" }
        var body: String? = null
        for (url in listOf("https://api.deepseek.com/models", "https://api.deepseek.com/v1/models")) {
            val result = runCatching {
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $apiKey")
                    .header("Accept", "application/json")
                    .get()
                    .build()
                ApiRequestExecutor.execute(client, request, "DeepSeek 模型探针")
            }.getOrNull()
            if (result != null) {
                body = result
                break
            }
        }
        val responseBody = body ?: error("DeepSeek 模型接口不可达")
        val data = json.parseToJsonElement(responseBody).jsonObject["data"]?.jsonArray.orEmpty()
        ModelCatalog.normalizeDeepSeekModels(
            data.mapNotNull { item -> item.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
        )
            .also { require(it.isNotEmpty()) { "服务端未返回可用模型" } }
    }
}
