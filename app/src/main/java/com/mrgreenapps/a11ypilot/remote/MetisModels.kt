package com.mrgreenapps.a11ypilot.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `metis.cli_attach.v1` 协议的数据模型。
 *
 * 协议本体是 HTTP + SSE，响应体统一为 JSON。桌面端的事件流对应 `metis.agent_event.v1/v2`，
 * 但字段命名在不同版本间存在差异（`type`/`event`、`request_id`/`requestId` 等），因此这里
 * 不采用强类型的 @Serializable 解码，而是用 kotlinx.serialization.json 的 JsonElement 做
 * 容错解析，保证同一套代码能兼容 v1/v2 的多种字段拼写。
 */

/** 会话。createSession 的响应。 */
data class MetisSession(
    val id: String,
    val label: String? = null
)

/** 一次任务运行。submitRun 的响应。 */
data class MetisRun(
    val id: String,
    val status: String? = null
)

/**
 * 事件流中的单条事件。
 *
 * 覆盖任务要求的四类事件：assistant 文本增量、工具调用、状态变化、权限请求；另有
 * [Unknown] 兜底，保证未知事件不会中断流收集，UI 层可忽略或展示原始类型。
 */
sealed class MetisEvent {
    abstract val id: String?

    /** assistant 文本增量（流式渲染的主体）。 */
    data class TextDelta(override val id: String?, val text: String) : MetisEvent()

    /** 一次工具调用（含参数，方便 UI 做标记展示）。 */
    data class ToolCall(
        override val id: String?,
        val toolCallId: String?,
        val name: String,
        val input: String
    ) : MetisEvent()

    /** 运行/会话状态变化。 */
    data class Status(override val id: String?, val status: String, val detail: String) : MetisEvent() {
        /** 是否为终态（完成/失败/取消），用于把连接状态从「运行中」切回「已连接」。 */
        val isTerminal: Boolean
            get() = status.lowercase() in TERMINAL_STATES

        val isError: Boolean
            get() = status.lowercase() in ERROR_STATES

        private companion object {
            val TERMINAL_STATES = setOf(
                "completed", "complete", "done", "succeeded", "success",
                "failed", "error", "cancelled", "canceled", "stopped"
            )
            val ERROR_STATES = setOf("failed", "error", "cancelled", "canceled")
        }
    }

    /** 权限请求：桌面端执行敏感操作前，要求手机端回传允许/拒绝。 */
    data class PermissionRequest(
        override val id: String?,
        val requestId: String,
        val tool: String,
        val summary: String
    ) : MetisEvent()

    /** 未识别的事件，保留原始类型名与原始数据。 */
    data class Unknown(override val id: String?, val type: String, val raw: String) : MetisEvent()
}

/**
 * 把 SSE `data:` 行的 JSON 负载解析为 [MetisEvent]。
 *
 * 注意：本项目复用 [com.mrgreenapps.a11ypilot.agent.ApiRequestExecutor.executeStream]，
 * 它已剥离 `data:` 前缀并只吐出负载字符串，因此这里入参就是裸 JSON。
 */
object MetisEventParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(data: String): MetisEvent? {
        if (data.isBlank()) return null
        val root = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return null
        return parseFromJson(root, data)
    }

    /** 从已解析的 [JsonObject] 直接解析事件（供 WebSocket 传输层复用，避免二次序列化）。 */
    fun parseFromJson(root: JsonObject, rawFallback: String = ""): MetisEvent? = parseEvent(root, rawFallback)

    private fun parseEvent(root: JsonObject, raw: String): MetisEvent? {
        // 事件类型字段有多种拼写：type / event / event_type。
        val type = root["type"]?.jsonPrimitive?.contentOrNull
            ?: root["event"]?.jsonPrimitive?.contentOrNull
            ?: root["event_type"]?.jsonPrimitive?.contentOrNull
            ?: ""
        val id = root["id"]?.jsonPrimitive?.contentOrNull
            ?: root["event_id"]?.jsonPrimitive?.contentOrNull

        // 统一成小写 + 点号分隔，避免 v1/v2 用冒号或下划线导致匹配失败。
        val normalized = type.lowercase().replace(':', '.').replace(' ', '_')

        return when {
            normalized in TEXT_DELTA_TYPES -> MetisEvent.TextDelta(id, textOf(root))
            normalized in TOOL_CALL_TYPES -> MetisEvent.ToolCall(
                id = id,
                toolCallId = root["tool_call_id"]?.jsonPrimitive?.contentOrNull
                    ?: root["call_id"]?.jsonPrimitive?.contentOrNull,
                name = root["name"]?.jsonPrimitive?.contentOrNull
                    ?: root["tool"]?.jsonPrimitive?.contentOrNull
                    ?: root["tool_name"]?.jsonPrimitive?.contentOrNull
                    ?: "tool",
                input = root["input"]?.let(::stringify)
                    ?: root["arguments"]?.let(::stringify)
                    ?: root["args"]?.let(::stringify)
                    ?: ""
            )
            normalized in STATUS_TYPES -> MetisEvent.Status(
                id = id,
                status = root["status"]?.jsonPrimitive?.contentOrNull
                    ?: root["state"]?.jsonPrimitive?.contentOrNull
                    ?: root["phase"]?.jsonPrimitive?.contentOrNull
                    ?: "",
                detail = root["message"]?.jsonPrimitive?.contentOrNull
                    ?: root["detail"]?.jsonPrimitive?.contentOrNull
                    ?: ""
            )
            normalized in PERMISSION_TYPES -> MetisEvent.PermissionRequest(
                id = id,
                requestId = root["request_id"]?.jsonPrimitive?.contentOrNull
                    ?: root["requestId"]?.jsonPrimitive?.contentOrNull
                    ?: id
                    ?: "",
                tool = root["tool"]?.jsonPrimitive?.contentOrNull
                    ?: root["tool_name"]?.jsonPrimitive?.contentOrNull
                    ?: "",
                summary = root["summary"]?.jsonPrimitive?.contentOrNull
                    ?: root["message"]?.jsonPrimitive?.contentOrNull
                    ?: root["description"]?.jsonPrimitive?.contentOrNull
                    ?: ""
            )
            else -> MetisEvent.Unknown(id, type, raw)
        }
    }

    /** 提取文本：兼容 `delta` / `text` / 字符串型 `content` 三种负载。 */
    private fun textOf(root: kotlinx.serialization.json.JsonObject): String =
        root["delta"]?.jsonPrimitive?.contentOrNull
            ?: root["text"]?.jsonPrimitive?.contentOrNull
            ?: root["content"]?.jsonPrimitive?.contentOrNull
            ?: ""

    /** 工具参数可能是字符串，也可能是对象/数组；统一压平成字符串供 UI 展示。 */
    private fun stringify(element: kotlinx.serialization.json.JsonElement): String =
        if (element is JsonPrimitive) element.contentOrNull.orEmpty() else element.toString()

    private val TEXT_DELTA_TYPES = setOf(
        "assistant_text_delta", "assistant.text.delta", "text_delta", "text.delta",
        "message.delta", "assistant_message", "assistant.message",
        "response.output_text.delta", "content_block_delta", "content_block_delta.text"
    )

    private val TOOL_CALL_TYPES = setOf(
        "tool_use", "tool_call", "assistant.tool_call", "assistant_tool_call",
        "tool_use.input", "tool_call.input", "function_call", "tool_use.delta"
    )

    private val STATUS_TYPES = setOf(
        "status", "run.status", "run_status", "state", "run.state", "run_state",
        "phase", "run.completed", "run.failed", "run.cancelled",
        "session.status", "session_status"
    )

    private val PERMISSION_TYPES = setOf(
        "permission_request", "permission_required", "permission",
        "approval_required", "approval_request", "permission.pending", "tool_permission"
    )
}
