package com.mrgreenapps.a11ypilot.agent

import com.mrgreenapps.a11ypilot.data.ReasoningIntensity
import com.mrgreenapps.a11ypilot.data.WorkMode
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

/** Chat-completions adapter retained for DeepSeek's official API. */
class OpenAICompatibleClient(
    private val apiKey: String,
    private val model: String,
    private val mode: WorkMode,
    private val reasoningIntensity: ReasoningIntensity,
    baseUrl: String,
    private val supportsReasoningEffort: Boolean,
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
    private val customSystemPrompt: String? = null
) {
    private val endpoint = "${baseUrl.trimEnd('/').removeSuffix("/v1")}/v1/chat/completions"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val http = HttpClients.shared

    suspend fun complete(history: List<AnthropicClient.Message>): AnthropicClient.Reply =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("model", model)
                putJsonArray("messages") {
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", customSystemPrompt
                            ?: if (groupMembers.isNotEmpty()) {
                                Prompts.systemForGroup(mode, groupMembers, userName)
                            } else {
                                Prompts.systemForMode(mode, personaInstruction, characterCard, userName)
                            })
                    })
                    history.forEach { appendMessage(it) }
                }
                putJsonArray("tools") {
                    Prompts.anthropicTools(mode, phoneEnabled).forEach { tool ->
                        val source = tool.jsonObject
                        add(buildJsonObject {
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", source.getValue("name"))
                                source["description"]?.let { put("description", it) }
                                put("parameters", source.getValue("input_schema"))
                            }
                        })
                    }
                }
                put("tool_choice", "auto")
                if (supportsReasoningEffort) {
                    put("reasoning_effort", reasoningIntensity.apiValue)
                }
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

            val responseText = ApiRequestExecutor.execute(http, request, "OpenAI 兼容 API")
            parseReply(responseText)
        }

    private fun kotlinx.serialization.json.JsonArrayBuilder.appendMessage(message: AnthropicClient.Message) {
        when (message) {
            is AnthropicClient.Message.User -> appendUserContent(message.content)
            is AnthropicClient.Message.Assistant -> add(buildJsonObject {
                put("role", "assistant")
                val text = message.content
                    .filter { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
                    .mapNotNull { it["text"]?.jsonPrimitive?.contentOrNull }
                    .joinToString("\n")
                put("content", text)
                val calls = message.content.filter {
                    it["type"]?.jsonPrimitive?.contentOrNull == "tool_use"
                }
                if (calls.isNotEmpty()) {
                    putJsonArray("tool_calls") {
                        calls.forEach { call ->
                            add(buildJsonObject {
                                put("id", call.getValue("id"))
                                put("type", "function")
                                putJsonObject("function") {
                                    put("name", call.getValue("name"))
                                    val arguments = call["input"]?.jsonObject ?: JsonObject(emptyMap())
                                    put("arguments", json.encodeToString(JsonObject.serializer(), arguments))
                                }
                            })
                        }
                    }
                }
            })
        }
    }

    private fun kotlinx.serialization.json.JsonArrayBuilder.appendUserContent(content: List<JsonObject>) {
        val toolResults = content.filter {
            it["type"]?.jsonPrimitive?.contentOrNull == "tool_result"
        }
        if (toolResults.isNotEmpty()) {
            toolResults.forEach { result ->
                add(buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", result.getValue("tool_use_id"))
                    put("content", extractToolResultText(result))
                })
                extractToolResultImage(result)?.let { dataUrl ->
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", "这是刚才工具返回的屏幕截图。")
                            })
                            add(buildJsonObject {
                                put("type", "image_url")
                                putJsonObject("image_url") { put("url", dataUrl) }
                            })
                        }
                    })
                }
            }
            return
        }

        val text = content.mapNotNull { block ->
            block["text"]?.jsonPrimitive?.contentOrNull
        }.joinToString("\n")
        add(buildJsonObject {
            put("role", "user")
            put("content", text)
        })
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

    private fun parseReply(responseText: String): AnthropicClient.Reply {
        val root = json.parseToJsonElement(responseText).jsonObject
        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw RuntimeException("OpenAI 兼容 API 未返回 choices")
        val finishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull ?: "stop"
        val message = choice["message"]?.jsonObject
            ?: throw RuntimeException("OpenAI 兼容 API 未返回 message")
        val blocks = mutableListOf<JsonObject>()
        message["content"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { text ->
            blocks += buildJsonObject { put("type", "text"); put("text", text) }
        }
        val toolUses = message["tool_calls"]?.jsonArray.orEmpty().mapNotNull { element ->
            val call = element.jsonObject
            val function = call["function"]?.jsonObject ?: return@mapNotNull null
            val id = call["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val name = function["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val rawArguments = function["arguments"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val input = runCatching { json.parseToJsonElement(rawArguments).jsonObject }
                .getOrElse { JsonObject(emptyMap()) }
            blocks += buildJsonObject {
                put("type", "tool_use")
                put("id", id)
                put("name", name)
                put("input", input)
            }
            AnthropicClient.ToolUse(id, name, input)
        }
        val usage = root["usage"]?.jsonObject
        val promptDetails = usage?.get("prompt_tokens_details")?.jsonObject
        return AnthropicClient.Reply(
            stopReason = finishReason,
            assistantContent = blocks,
            toolUses = toolUses,
            inputTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
            cachedInputTokens = promptDetails?.get("cached_tokens")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
            cacheCreationInputTokens = 0,
            outputTokens = usage?.get("completion_tokens")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        )
    }

    private fun JsonElement?.orEmpty(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
