package com.mrgreenapps.a11ypilot.agent

import com.mrgreenapps.a11ypilot.data.ReasoningIntensity
import com.mrgreenapps.a11ypilot.data.WorkMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
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

/** PinAI/OpenAI Responses API adapter with function-call round trips. */
class OpenAIResponsesClient(
    private val apiKey: String,
    private val model: String,
    private val mode: WorkMode,
    private val reasoningIntensity: ReasoningIntensity,
    private val personaInstruction: String = "",
    private val characterCard: CharacterCard? = null,
    private val phoneEnabled: Boolean = true,
    private val userName: String = "",
    private val groupMembers: List<CharacterCard> = emptyList(),
    /**
     * Optional override for the system prompt. When non-null, it replaces the [groupMembers]
     * / [characterCard] prompt assembly — used by [GroupCoordinator] to give each member its
     * own persona-specific prompt without having to clone the HTTP plumbing.
     */
    private val customSystemPrompt: String? = null,
    baseUrl: String
) {
    private val endpoint = "${baseUrl.trimEnd('/').removeSuffix("/v1")}/v1/responses"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val http = HttpClients.shared

    suspend fun complete(history: List<AnthropicClient.Message>): AnthropicClient.Reply =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .header("User-Agent", MetisClientIdentity.value)
                .header("X-Metis-Client", MetisClientIdentity.value)
                .header("X-Metis-Client-Platform", MetisClientIdentity.PLATFORM)
                .post(
                    json.encodeToString(JsonObject.serializer(), buildRequestBody(history))
                        .toRequestBody(JSON_MEDIA)
                )
                .build()
            parseReply(ApiRequestExecutor.execute(http, request, "OpenAI Responses API"))
        }

    /**
     * Streams the final assistant text via SSE. Emits incremental text deltas as they arrive.
     * Used by the engine to show a long answer progressively instead of waiting for the whole
     * response. Falls back to empty when the stream yields no text (caller should then use
     * [complete]).
     */
    fun completeStream(history: List<AnthropicClient.Message>): kotlinx.coroutines.flow.Flow<String> =
        kotlinx.coroutines.flow.flow {
            val base = buildRequestBody(history)
            val body = buildJsonObject {
                base.forEach { (k, v) -> put(k, v) }
                put("stream", JsonPrimitive(true))
            }
            val request = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .header("User-Agent", MetisClientIdentity.value)
                .header("X-Metis-Client", MetisClientIdentity.value)
                .header("X-Metis-Client-Platform", MetisClientIdentity.PLATFORM)
                .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA))
                .build()
            ApiRequestExecutor.executeStream(http, request).collect { data ->
                val delta = runCatching {
                    val obj = json.parseToJsonElement(data).jsonObject
                    when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                        "response.output_text.delta" -> obj["delta"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        else -> ""
                    }
                }.getOrDefault("")
                if (delta.isNotEmpty()) emit(delta)
            }
        }

    internal fun buildRequestBody(history: List<AnthropicClient.Message>): JsonObject = buildJsonObject {
        put("model", model)
        put("instructions", customSystemPrompt
            ?: if (groupMembers.isNotEmpty()) {
                Prompts.systemForGroup(mode, groupMembers, userName)
            } else {
                Prompts.systemForMode(mode, personaInstruction, characterCard, userName)
            })
        put("store", false)
        put("parallel_tool_calls", false)
        putJsonObject("reasoning") { put("effort", reasoningIntensity.apiValue) }
        putJsonArray("input") {
            history.forEach { message ->
                when (message) {
                    is AnthropicClient.Message.User -> appendUser(message.content)
                    is AnthropicClient.Message.Assistant -> appendAssistant(message.content)
                }
            }
        }
        putJsonArray("tools") {
            Prompts.anthropicTools(mode, phoneEnabled).forEach { element ->
                val source = element.jsonObject
                add(buildJsonObject {
                    put("type", "function")
                    put("name", source.getValue("name"))
                    source["description"]?.let { put("description", it) }
                    put("parameters", source.getValue("input_schema"))
                    put("strict", true)
                })
            }
        }
    }

    private fun kotlinx.serialization.json.JsonArrayBuilder.appendUser(content: List<JsonObject>) {
        val toolResults = content.filter { it["type"]?.jsonPrimitive?.contentOrNull == "tool_result" }
        if (toolResults.isNotEmpty()) {
            toolResults.forEach { result ->
                add(buildJsonObject {
                    put("type", "function_call_output")
                    put("call_id", result.getValue("tool_use_id"))
                    put("output", extractToolResultText(result))
                })
                extractToolResultImage(result)?.let { dataUrl ->
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            add(buildJsonObject {
                                put("type", "input_text")
                                put("text", "这是刚才工具返回的屏幕截图。")
                            })
                            add(buildJsonObject {
                                put("type", "input_image")
                                put("image_url", dataUrl)
                            })
                        }
                    })
                }
            }
            return
        }

        val text = content.mapNotNull { it["text"]?.jsonPrimitive?.contentOrNull }.joinToString("\n")
        add(buildJsonObject {
            put("role", "user")
            putJsonArray("content") {
                add(buildJsonObject { put("type", "input_text"); put("text", text) })
            }
        })
    }

    private fun kotlinx.serialization.json.JsonArrayBuilder.appendAssistant(content: List<JsonObject>) {
        val text = content
            .filter { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
            .mapNotNull { it["text"]?.jsonPrimitive?.contentOrNull }
            .joinToString("\n")
        if (text.isNotBlank()) {
            add(buildJsonObject { put("role", "assistant"); put("content", text) })
        }
        content.filter { it["type"]?.jsonPrimitive?.contentOrNull == "tool_use" }.forEach { call ->
            add(buildJsonObject {
                put("type", "function_call")
                put("call_id", call.getValue("id"))
                put("name", call.getValue("name"))
                val arguments = call["input"]?.jsonObject ?: JsonObject(emptyMap())
                put("arguments", json.encodeToString(JsonObject.serializer(), arguments))
            })
        }
    }

    internal fun parseReply(responseText: String): AnthropicClient.Reply {
        val root = json.parseToJsonElement(responseText).jsonObject
        val output = root["output"] as? JsonArray ?: JsonArray(emptyList())
        val blocks = mutableListOf<JsonObject>()
        val toolUses = mutableListOf<AnthropicClient.ToolUse>()

        output.forEach { element ->
            val item = element.jsonObject
            when (item["type"]?.jsonPrimitive?.contentOrNull) {
                "message" -> item["content"]?.jsonArray.orEmpty().forEach { contentElement ->
                    val content = contentElement.jsonObject
                    if (content["type"]?.jsonPrimitive?.contentOrNull in setOf("output_text", "text")) {
                        content["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { text ->
                            blocks += buildJsonObject { put("type", "text"); put("text", text) }
                        }
                    }
                }
                "function_call" -> {
                    val id = item["call_id"]?.jsonPrimitive?.contentOrNull
                        ?: item["id"]?.jsonPrimitive?.contentOrNull
                        ?: return@forEach
                    val name = item["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    val rawArguments = item["arguments"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val input = runCatching { json.parseToJsonElement(rawArguments).jsonObject }
                        .getOrElse { JsonObject(emptyMap()) }
                    blocks += buildJsonObject {
                        put("type", "tool_use")
                        put("id", id)
                        put("name", name)
                        put("input", input)
                    }
                    toolUses += AnthropicClient.ToolUse(id, name, input)
                }
            }
        }

        if (blocks.isEmpty()) {
            root["output_text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { text ->
                blocks += buildJsonObject { put("type", "text"); put("text", text) }
            }
        }
        if (blocks.isEmpty()) {
            val status = root["status"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            val detail = root["incomplete_details"]?.toString()?.take(240).orEmpty()
            throw ApiCallException("OpenAI Responses API 未返回可见文本或工具调用（status=$status）${detail.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()}")
        }

        val usage = root["usage"]?.jsonObject
        val inputDetails = usage?.get("input_tokens_details")?.jsonObject
        return AnthropicClient.Reply(
            stopReason = root["status"]?.jsonPrimitive?.contentOrNull ?: "completed",
            assistantContent = blocks,
            toolUses = toolUses,
            inputTokens = usage?.get("input_tokens")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
            cachedInputTokens = inputDetails?.get("cached_tokens")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
            cacheCreationInputTokens = 0,
            outputTokens = usage?.get("output_tokens")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        )
    }

    private fun extractToolResultText(result: JsonObject): String {
        val value = result["content"] ?: return ""
        return when (value) {
            is JsonPrimitive -> value.contentOrNull.orEmpty()
            is JsonArray -> value.mapNotNull { block ->
                block.jsonObject["text"]?.jsonPrimitive?.contentOrNull
            }.joinToString("\n")
            else -> value.toString()
        }
    }

    private fun extractToolResultImage(result: JsonObject): String? {
        val blocks = result["content"] as? JsonArray ?: return null
        val source = blocks.firstNotNullOfOrNull { block ->
            val obj = block.jsonObject
            if (obj["type"]?.jsonPrimitive?.contentOrNull != "image") return@firstNotNullOfOrNull null
            obj["source"]?.jsonObject
        } ?: return null
        val mime = source["media_type"]?.jsonPrimitive?.contentOrNull ?: "image/jpeg"
        val data = source["data"]?.jsonPrimitive?.contentOrNull ?: return null
        return "data:$mime;base64,$data"
    }

    private fun kotlinx.serialization.json.JsonElement?.orEmpty(): JsonArray =
        this as? JsonArray ?: JsonArray(emptyList())

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
