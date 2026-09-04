package com.mrgreenapps.a11ypilot.utils

import com.mrgreenapps.a11ypilot.data.Message
import com.mrgreenapps.a11ypilot.data.MessageRole
import com.mrgreenapps.a11ypilot.data.MessageStatus
import com.mrgreenapps.a11ypilot.data.Session
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 群组/单条会话导出工具：把 [Session] + [Message] 列表转成 Markdown / JSON 字符串，
 * 方便通过系统分享面板发给同事、贴到 Notion / 微信收藏等。
 *
 * 设计目标：
 *  - Markdown 优先给人看：每条消息一行时间戳 + 发言人 + 多行内容，工具调用展开为子项。
 *  - JSON 优先给程序消费：保持原始 [Message] 字段（含 speakerId / speakerName）便于回放。
 *  - 不区分群组 vs 单条：群组会话天然带 speakerId/speakerName，单条一律渲染为「Metis」。
 */
object GroupExporter {
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.SIMPLIFIED_CHINESE)

    fun toMarkdown(session: Session, messages: List<Message>): String {
        val sb = StringBuilder()
        sb.append("# ").append(session.title).append('\n')
        sb.append("- 会话模式：").append(session.mode.name).append('\n')
        sb.append("- 模型：").append(session.provider.displayName).append(' ').append(session.model).append('\n')
        sb.append("- 会话 ID：").append(session.id).append('\n')
        sb.append("- 消息数：").append(messages.size).append('\n')
        if (session.groupMemberIds.isNotEmpty()) {
            sb.append("- 群组成员数：").append(session.groupMemberIds.size).append('\n')
        }
        if (session.summary?.isNotBlank() == true) {
            sb.append("\n## 上次摘要\n\n").append(session.summary.trim()).append("\n")
        }
        sb.append("\n## 完整记录\n\n")
        for (msg in messages) {
            val speaker = when {
                msg.role == MessageRole.USER -> "👤 用户"
                msg.speakerName?.isNotBlank() == true -> "🤖 ${msg.speakerName}"
                else -> "🤖 Metis"
            }
            sb.append("- **").append(timeFormat.format(Date(msg.timestamp))).append("** · ")
                .append(speaker)
                .append(msg.status.let { if (it == MessageStatus.ERROR) " · ⚠️ 失败" else "" })
                .append('\n')
            if (msg.content.isNotBlank()) {
                sb.append(msg.content.trim()).append('\n')
            }
            msg.toolCalls?.takeIf { it.isNotEmpty() }?.let { calls ->
                sb.append("  - 调用工具：")
                    .append(calls.joinToString("、") { it.name.substringBefore('(') })
                    .append('\n')
            }
            msg.attachments?.takeIf { it.isNotEmpty() }?.let { paths ->
                sb.append("  - 附件：").append(paths.joinToString("、") { java.io.File(it).name }).append('\n')
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    fun toJson(session: Session, messages: List<Message>): String {
        val payload: JsonObject = buildJsonObject {
            put("session", sessionJson(session))
            put("messages", buildJsonArray {
                messages.forEach { add(messageJson(it)) }
            })
            put("exportedAt", System.currentTimeMillis())
            put("exportedAtIso", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date()))
        }
        return Json { prettyPrint = true; encodeDefaults = true }.encodeToString(payload)
    }

    private fun sessionJson(session: Session): JsonObject = buildJsonObject {
        put("id", session.id)
        put("title", session.title)
        put("mode", session.mode.name)
        put("provider", session.provider.displayName)
        put("model", session.model)
        put("createdAt", session.createdAt)
        put("lastActiveAt", session.lastActiveAt)
        put("groupMemberIds", buildJsonArray { session.groupMemberIds.forEach { add(it) } })
        session.summary?.takeIf { it.isNotBlank() }?.let { put("summary", it) }
    }

    private fun messageJson(message: Message): JsonObject = buildJsonObject {
        put("id", message.id)
        put("role", message.role.name)
        put("content", message.content)
        put("timestamp", message.timestamp)
        put("status", message.status.name)
        message.speakerId?.let { put("speakerId", it) }
        message.speakerName?.let { put("speakerName", it) }
        message.toolCalls?.takeIf { it.isNotEmpty() }?.let { calls ->
            put("toolCalls", buildJsonArray {
                calls.forEach { call ->
                    add(buildJsonObject {
                        put("name", call.name)
                        put("timestamp", call.timestamp)
                        put("status", call.status)
                    })
                }
            })
        }
        message.attachments?.takeIf { it.isNotEmpty() }?.let { paths ->
            put("attachments", buildJsonArray { paths.forEach { add(it) } })
        }
    }
}