package com.mrgreenapps.a11ypilot

import com.mrgreenapps.a11ypilot.data.Message
import com.mrgreenapps.a11ypilot.data.MessageRole
import com.mrgreenapps.a11ypilot.data.MessageStatus
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 一组针对群组多智能体写入的 Message 字段（speakerId / speakerName）的
 * 序列化/反序列化契约测试。覆盖：
 *   - 新字段写入后能正确序列化为 JSON，下游流式 Read 也能再读回来。
 *   - 老记录（缺字段）解码后默认填 null，向前兼容。
 *   - JSON encodeDefaults 的策略没让空值字段被无意义地持久化出来。
 *
 * 直接复用 SessionRepository 内部的 [repositoryJson] 风格（ignoreUnknownKeys +
 * coerceInputValues + encodeDefaults）以贴近产品路径。
 */
class MessageSpeakerSerializationTest {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        encodeDefaults = true
    }

    private fun sampleMessage(
        speakerId: String? = null,
        speakerName: String? = null
    ) = Message(
        id = "msg-1",
        sessionId = "session-1",
        role = MessageRole.ASSISTANT,
        content = "hello",
        timestamp = 1_700_000_000_000L,
        status = MessageStatus.COMPLETE,
        speakerId = speakerId,
        speakerName = speakerName
    )

    @Test
    fun roundTrip_preservesSpeakerFieldsWhenSet() {
        val original = sampleMessage(
            speakerId = "alice-card-id",
            speakerName = "Alice"
        )

        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<Message>(encoded)

        assertEquals(original.speakerId, decoded.speakerId)
        assertEquals(original.speakerName, decoded.speakerName)
        assertEquals(original.content, decoded.content)
        assertEquals(original.role, decoded.role)
        assertEquals(original.status, decoded.status)
    }

    @Test
    fun roundTrip_leavesSpeakerFieldsNullWhenAbsent() {
        val original = sampleMessage() // both null

        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<Message>(encoded)

        assertNull(decoded.speakerId)
        assertNull(decoded.speakerName)
    }

    /**
     * 模拟老版本会话记录里没有 speakerId/speakerName 字段，反序列化时应当回退为 null，
     * 不要抛异常或写到错值上。
     */
    @Test
    fun decode_legacyRecordWithoutSpeakerFields_returnsNullSpeaker() {
        val legacyJson = """
            {
              "id": "old-msg",
              "sessionId": "old-session",
              "role": "ASSISTANT",
              "content": "记忆里的旧回复",
              "timestamp": 1699000000000,
              "status": "COMPLETE",
              "metadata": null,
              "toolCalls": null,
              "contextTokens": 0,
              "isCompacting": false
            }
        """.trimIndent()

        val decoded = json.decodeFromString<Message>(legacyJson)
        assertEquals("old-msg", decoded.id)
        assertNull("legacy record must default speakerId to null", decoded.speakerId)
        assertNull("legacy record must default speakerName to null", decoded.speakerName)
        assertEquals("记忆里的旧回复", decoded.content)
    }

    /**
     * 把同一个会话里多条不同发言人的回复放进 list encode/decode，确保
     * SessionRepository.decodeMessages 这条路径不会把它们混在一起。
     */
    @Test
    fun listRoundTrip_preservesEachMessageSpeakerIdentity() {
        val messages = listOf(
            sampleMessage(speakerId = "alice-card", speakerName = "Alice"),
            sampleMessage(speakerId = "bob-card", speakerName = "Bob")
                .copy(id = "msg-2", content = "我同意 Alice。")
        )

        val encoded = json.encodeToString(messages)
        // 串里包含两个不同的发言人 id，不能被合并。
        assertTrue(encoded.contains("alice-card"))
        assertTrue(encoded.contains("bob-card"))
        assertFalse("不应丢失空 speaker 字段（保持 encodeDefaults 行为）", encoded.contains("\"speakerId\":null,\"speakerName\":null"))

        val decoded = json.decodeFromString<List<Message>>(encoded)
        assertEquals(2, decoded.size)
        assertEquals("alice-card", decoded[0].speakerId)
        assertEquals("Alice", decoded[0].speakerName)
        assertEquals("bob-card", decoded[1].speakerId)
        assertEquals("Bob", decoded[1].speakerName)
    }

    /**
     * helper: 断言列表 decode 不会因为空 speakerId 字段而走特殊语义。
     */
    @Test
    fun decode_singleLegacyList_returnsAll() {
        val raw = """[{"id":"a","sessionId":"s","role":"ASSISTANT","content":"hi","timestamp":1,"status":"COMPLETE","metadata":null,"toolCalls":null,"contextTokens":0,"isCompacting":false}]"""
        val decoded = json.decodeFromString<List<Message>>(raw)
        assertEquals(1, decoded.size)
        assertNotNull(decoded.first())
    }
}
