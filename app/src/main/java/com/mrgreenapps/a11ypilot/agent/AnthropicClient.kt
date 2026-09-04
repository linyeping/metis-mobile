package com.mrgreenapps.a11ypilot.agent

import com.mrgreenapps.a11ypilot.data.WorkMode
import com.mrgreenapps.a11ypilot.data.ModelProvider
import com.mrgreenapps.a11ypilot.data.ReasoningIntensity
import com.mrgreenapps.a11ypilot.data.ReasoningCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Minimal client for Anthropic's /v1/messages tool-use API.
 * Caches the system block + tools so subsequent turns hit the prompt cache.
 */
class AnthropicClient(
    private val apiKey: String,
    model: String,
    private val mode: WorkMode,
    private val reasoningIntensity: ReasoningIntensity = ReasoningIntensity.MEDIUM,
    private val maxOutputTokens: Int = 4096,
    private val baseUrl: String = "https://api.anthropic.com",
    private val personaInstruction: String = ""
) {
    private val endpoint = "${baseUrl.trimEnd('/').removeSuffix("/v1")}/v1/messages"
    // NOTE: this class is currently unused by the engine (AgentEngine routes to
    // OpenAIResponsesClient / OpenAICompatibleClient). The legacy short-name→API-id mapping below
    // is retained only for potential re-enablement and MUST NOT be trusted as current model ids.
    private val model: String = when (model) {
        else -> model
    }
    private val http = HttpClients.shared

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    /** A turn-message used by the caller to maintain conversation history. */
    sealed class Message {
        data class User(val content: List<JsonObject>) : Message()
        data class Assistant(val content: List<JsonObject>) : Message()
    }

    data class StopBlock(val type: String, val payload: JsonObject)

    data class Reply(
        val stopReason: String,
        /** All assistant content blocks, suitable for round-tripping back to the API. */
        val assistantContent: List<JsonObject>,
        /** Convenience: just the tool_use blocks the loop needs to dispatch. */
        val toolUses: List<ToolUse>,
        val inputTokens: Int,
        val cachedInputTokens: Int,
        val cacheCreationInputTokens: Int,
        val outputTokens: Int
    )

    data class ToolUse(val id: String, val name: String, val input: JsonObject)

    suspend fun complete(history: List<Message>): Reply = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("model", model)
            val thinkingBudget = when (reasoningIntensity) {
                ReasoningIntensity.LOW -> 1024
                ReasoningIntensity.MEDIUM -> 4096
                ReasoningIntensity.HIGH -> 8192
                ReasoningIntensity.XHIGH -> 12288
                ReasoningIntensity.MAX -> 16384
            }
            val extendedThinking = model.endsWith("-thinking")
            put("max_tokens", if (extendedThinking) maxOf(maxOutputTokens, thinkingBudget + 1024) else maxOutputTokens)
            if (extendedThinking) {
                putJsonObject("thinking") {
                    put("type", "enabled")
                    put("budget_tokens", thinkingBudget)
                }
            }
            if (reasoningIntensity in ReasoningCatalog.forModel(ModelProvider.CUSTOM_CLAUDE, model)) {
                putJsonObject("output_config") {
                    put("effort", reasoningIntensity.apiValue)
                }
            }
            putJsonArray("system") {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", Prompts.systemForMode(mode, personaInstruction))
                    putJsonObject("cache_control") { put("type", "ephemeral") }
                })
            }
            // Inject cache_control on the final tool so the entire tools block is cacheable.
            val tools = Prompts.anthropicTools(mode)
            putJsonArray("tools") {
                tools.forEachIndexed { idx, t ->
                    val obj = t.jsonObject
                    if (idx == tools.size - 1) {
                        add(buildJsonObject {
                            obj.forEach { (k, v) -> put(k, v) }
                            putJsonObject("cache_control") { put("type", "ephemeral") }
                        })
                    } else {
                        add(obj)
                    }
                }
            }
            putJsonArray("messages") {
                history.forEach { m ->
                    add(buildJsonObject {
                        when (m) {
                            is Message.User -> {
                                put("role", "user")
                                putJsonArray("content") { m.content.forEach { add(it) } }
                            }
                            is Message.Assistant -> {
                                put("role", "assistant")
                                putJsonArray("content") { m.content.forEach { add(it) } }
                            }
                        }
                    })
                }
            }
        }

        val requestBuilder = Request.Builder()
            .url(endpoint)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .header("User-Agent", MetisClientIdentity.value)
            .header("X-Metis-Client", MetisClientIdentity.value)
            .header("X-Metis-Client-Platform", MetisClientIdentity.PLATFORM)
            .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA))
        if (baseUrl.contains("api.anthropic.com", ignoreCase = true)) {
            requestBuilder.header("x-api-key", apiKey)
        } else {
            requestBuilder.header("Authorization", "Bearer $apiKey")
        }
        val req = requestBuilder.build()

        val text = ApiRequestExecutor.execute(http, req, "Claude API")
        run {
            val root = json.parseToJsonElement(text).jsonObject
            val stopReason = root["stop_reason"]?.jsonPrimitive?.contentOrNull ?: "end_turn"
            val contentArr: JsonArray = root["content"]?.jsonArray ?: JsonArray(emptyList())
            val blocks = contentArr.map { it.jsonObject }
            val toolUses = blocks.mapNotNull { b ->
                if (b["type"]?.jsonPrimitive?.contentOrNull == "tool_use") {
                    ToolUse(
                        id = b["id"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                        name = b["name"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                        input = b["input"]?.jsonObject ?: JsonObject(emptyMap())
                    )
                } else null
            }
            val usage = root["usage"]?.jsonObject
            Reply(
                stopReason = stopReason,
                assistantContent = blocks,
                toolUses = toolUses,
                inputTokens = usage?.get("input_tokens")?.jsonPrimitive?.intOrNull() ?: 0,
                cachedInputTokens = usage?.get("cache_read_input_tokens")?.jsonPrimitive?.intOrNull() ?: 0,
                cacheCreationInputTokens = usage?.get("cache_creation_input_tokens")?.jsonPrimitive?.intOrNull() ?: 0,
                outputTokens = usage?.get("output_tokens")?.jsonPrimitive?.intOrNull() ?: 0
            )
        }
    }

    /**
     * One result for a single tool_use. If [imageBase64] is set, the tool_result is emitted with
     * a content array containing an image block followed by the text — required when the model
     * needs to see pixels (screenshot tool).
     */
    data class ToolResult(
        val toolUseId: String,
        val text: String,
        val isError: Boolean = false,
        val imageBase64: String? = null,
        val imageMimeType: String? = null
    )

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /** Helper: build a user content list with a single text block. */
        fun userText(text: String): List<JsonObject> = listOf(buildJsonObject {
            put("type", "text"); put("text", text)
        })

        /** Helper: build a user content list of tool_result blocks (text- or image-bearing). */
        fun userToolResults(results: List<ToolResult>): List<JsonObject> =
            results.map { r ->
                buildJsonObject {
                    put("type", "tool_result")
                    put("tool_use_id", r.toolUseId)
                    if (r.imageBase64 != null) {
                        putJsonArray("content") {
                            add(buildJsonObject {
                                put("type", "image")
                                putJsonObject("source") {
                                    put("type", "base64")
                                    put("media_type", r.imageMimeType ?: "image/jpeg")
                                    put("data", r.imageBase64)
                                }
                            })
                            if (r.text.isNotEmpty()) {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", r.text)
                                })
                            }
                        }
                    } else {
                        put("content", r.text)
                    }
                    if (r.isError) put("is_error", true)
                }
            }
    }
}

private fun JsonPrimitive.intOrNull(): Int? = contentOrNull?.toIntOrNull()
