package com.mrgreenapps.a11ypilot

import androidx.compose.ui.graphics.Color
import com.mrgreenapps.a11ypilot.data.Message
import com.mrgreenapps.a11ypilot.data.MessageRole
import com.mrgreenapps.a11ypilot.data.MessageStatus
import com.mrgreenapps.a11ypilot.data.ModelProvider
import com.mrgreenapps.a11ypilot.data.ReasoningIntensity
import com.mrgreenapps.a11ypilot.data.Session
import com.mrgreenapps.a11ypilot.data.WorkMode
import com.mrgreenapps.a11ypilot.ui.SpeakerColors
import com.mrgreenapps.a11ypilot.utils.GroupExporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 群组生态相关 utility 的纯单测：
 *  - SpeakerColors 哈希颜色：相同 id 必须给同样颜色，不同 id 必须不同
 *  - GroupExporter Markdown：标题/发言人/工具/附件 都要出现在输出里
 *  - GroupExporter JSON：解析回来的字段和原数据一致
 */
class GroupExporterAndColorsTest {

    private val baseSession = Session(
        id = "session-1",
        title = "测试群组",
        mode = WorkMode.CHAT,
        provider = ModelProvider.CUSTOM_OPENAI,
        model = "gpt-test",
        reasoningIntensity = ReasoningIntensity.MEDIUM,
        createdAt = 1_700_000_000_000,
        lastActiveAt = 1_700_000_000_000,
        groupMemberIds = listOf("alice", "bob")
    )

    private val userMsg = Message(
        id = "u-1", sessionId = "session-1",
        role = MessageRole.USER,
        content = "@Alice @Bob 今天午饭谁请？",
        timestamp = 1_700_000_000_000
    )

    private val aliceReply = Message(
        id = "m-1", sessionId = "session-1",
        role = MessageRole.ASSISTANT,
        content = "我来吧，老规矩。",
        timestamp = 1_700_000_005_000,
        speakerId = "alice", speakerName = "Alice"
    )

    private val bobReply = Message(
        id = "m-2", sessionId = "session-1",
        role = MessageRole.ASSISTANT,
        content = "记下了，记你账上了。",
        timestamp = 1_700_000_010_000,
        speakerId = "bob", speakerName = "Bob",
        toolCalls = listOf(com.mrgreenapps.a11ypilot.data.ToolCall("set_alarm", 1_700_000_010_500))
    )

    @Test
    fun speakerColors_areStableForSameId() {
        val base = Color(0xFF888888)
        val a1 = SpeakerColors.speakerBubbleColor("alice", base)
        val a2 = SpeakerColors.speakerBubbleColor("alice", base)
        assertEquals("same id must hash to same color", a1, a2)
    }

    @Test
    fun speakerColors_divergeForDifferentIds() {
        val base = Color(0xFF888888)
        val a = SpeakerColors.speakerBubbleColor("alice", base)
        val b = SpeakerColors.speakerBubbleColor("bob", base)
        assertFalse("different ids must give different colors", a == b)
    }

    @Test
    fun speakerColors_handleBlankIdByFallingBackToBase() {
        val base = Color(0xFF123456)
        val resolved = SpeakerColors.speakerBubbleColor("", base)
        assertEquals("blank id must use the neutral base color", base.copy(alpha = 0.65f), resolved)
    }

    @Test
    fun exporter_markdown_includesHeaderAndSpeakers() {
        val md = GroupExporter.toMarkdown(baseSession, listOf(userMsg, aliceReply, bobReply))
        assertTrue("title missing", md.contains("# 测试群组"))
        assertTrue("user marker missing", md.contains("👤 用户"))
        assertTrue("Alice speaker marker missing", md.contains("🤖 Alice"))
        assertTrue("Bob speaker marker missing", md.contains("🤖 Bob"))
        assertTrue("Alice reply missing", md.contains("我来吧"))
        assertTrue("tool summary missing", md.contains("set_alarm"))
    }

    @Test
    fun exporter_json_roundtripsSpeakerFields() {
        val json = GroupExporter.toJson(baseSession, listOf(userMsg, aliceReply, bobReply))
        // Pretty-print format puts spaces around ":". 用 stripWhitespace 后比对更稳。
        val flat = json.replace(Regex("\\s+"), "")
        assertTrue("session title missing", flat.contains("\"title\":\"测试群组\""))
        assertTrue("alice speakerId missing", flat.contains("\"speakerId\":\"alice\""))
        assertTrue("bob speakerName missing", flat.contains("\"speakerName\":\"Bob\""))
        assertTrue("groupMemberIds array missing", flat.contains("\"groupMemberIds\""))
        assertTrue("set_alarm tool call missing", flat.contains("\"set_alarm\""))
        assertNotNull("must be valid JSON shape", json)
    }

    @Test
    fun exporter_markdown_marksErrorStatusWithWarningGlyph() {
        val errorMsg = aliceReply.copy(status = MessageStatus.ERROR, content = "")
        val md = GroupExporter.toMarkdown(baseSession, listOf(userMsg, errorMsg))
        assertTrue("error status must surface", md.contains("⚠️ 失败"))
    }
}