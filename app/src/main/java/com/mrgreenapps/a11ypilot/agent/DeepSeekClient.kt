package com.mrgreenapps.a11ypilot.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import com.mrgreenapps.a11ypilot.data.ModelCatalog
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * DeepSeek API client compatible with OpenAI format.
 * Used for cost-effective text generation tasks (97% cheaper than Claude for long text).
 */
class DeepSeekClient(
    private val apiKey: String,
    private val model: String = ModelCatalog.defaultFor(com.mrgreenapps.a11ypilot.data.ModelProvider.DEEPSEEK)
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    data class Usage(
        val promptTokens: Int = 0,
        val completionTokens: Int = 0,
        val totalTokens: Int = 0
    )

    data class ChatResponse(
        val content: String,
        val usage: Usage
    )

    /**
     * Simple chat completion - returns assistant's text response.
     */
    suspend fun chat(messages: JsonArray): ChatResponse = withContext(Dispatchers.IO) {
        val requestBody = buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                messages.forEach { add(it) }
            }
            put("temperature", 0.7)
            put("max_tokens", 4000)
        }.toString()

        val request = Request.Builder()
            .url("https://api.deepseek.com/v1/chat/completions")
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("User-Agent", MetisClientIdentity.value)
            .addHeader("X-Metis-Client", MetisClientIdentity.value)
            .addHeader("X-Metis-Client-Platform", MetisClientIdentity.PLATFORM)
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        val body = ApiRequestExecutor.execute(client, request, "DeepSeek API")
        run {
            val jsonResponse = json.parseToJsonElement(body).jsonObject

            val content = jsonResponse["choices"]
                ?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("message")
                ?.jsonObject?.get("content")
                ?.jsonPrimitive?.content
                ?: throw RuntimeException("No content in DeepSeek response")

            val usageObj = jsonResponse["usage"]?.jsonObject
            val usage = Usage(
                promptTokens = usageObj?.get("prompt_tokens")?.jsonPrimitive?.int ?: 0,
                completionTokens = usageObj?.get("completion_tokens")?.jsonPrimitive?.int ?: 0,
                totalTokens = usageObj?.get("total_tokens")?.jsonPrimitive?.int ?: 0
            )

            ChatResponse(content, usage)
        }
    }

    /**
     * Convert Claude message format to OpenAI format
     */
    fun convertFromClaudeFormat(claudeMessages: JsonArray): JsonArray {
        val openAIMessages = mutableListOf<JsonObject>()

        claudeMessages.forEach { msg ->
            val msgObj = msg.jsonObject
            val role = msgObj["role"]?.jsonPrimitive?.content ?: return@forEach

            when (role) {
                "user" -> {
                    val content = msgObj["content"]?.jsonPrimitive?.content ?: return@forEach
                    openAIMessages.add(buildJsonObject {
                        put("role", "user")
                        put("content", content)
                    })
                }
                "assistant" -> {
                    val content = msgObj["content"]?.jsonArray?.firstOrNull()
                        ?.jsonObject?.get("text")?.jsonPrimitive?.content
                        ?: return@forEach
                    openAIMessages.add(buildJsonObject {
                        put("role", "assistant")
                        put("content", content)
                    })
                }
            }
        }

        return JsonArray(openAIMessages)
    }
}
