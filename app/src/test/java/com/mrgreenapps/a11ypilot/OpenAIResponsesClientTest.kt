package com.mrgreenapps.a11ypilot

import com.mrgreenapps.a11ypilot.agent.AnthropicClient
import com.mrgreenapps.a11ypilot.agent.ApiCallException
import com.mrgreenapps.a11ypilot.agent.OpenAIResponsesClient
import com.mrgreenapps.a11ypilot.data.ReasoningIntensity
import com.mrgreenapps.a11ypilot.data.WorkMode
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAIResponsesClientTest {
    private val client = OpenAIResponsesClient(
        apiKey = "test-key",
        model = "gpt-5.5",
        mode = WorkMode.CODE,
        reasoningIntensity = ReasoningIntensity.XHIGH,
        baseUrl = "https://example.test/v1"
    )

    @Test
    fun requestUsesResponsesReasoningAndFunctionSchema() {
        val body = client.buildRequestBody(
            listOf(AnthropicClient.Message.User(AnthropicClient.userText("run tests")))
        )

        assertEquals("gpt-5.5", body.getValue("model").jsonPrimitive.content)
        assertFalse(body.getValue("store").jsonPrimitive.boolean)
        assertEquals("xhigh", body.getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content)
        assertTrue(body.getValue("input").jsonArray.isNotEmpty())
        assertTrue(body.getValue("tools").jsonArray.all {
            it.jsonObject.getValue("type").jsonPrimitive.content == "function"
        })
    }

    @Test
    fun responseParsesVisibleTextAndFunctionCalls() {
        val reply = client.parseReply(
            """{
              "status":"completed",
              "output":[
                {"type":"message","content":[{"type":"output_text","text":"完成"}]},
                {"type":"function_call","call_id":"call_1","name":"done","arguments":"{\"success\":true,\"summary\":\"完成\"}"}
              ],
              "usage":{"input_tokens":12,"output_tokens":7,"input_tokens_details":{"cached_tokens":3}}
            }""".trimIndent()
        )

        assertEquals("完成", reply.assistantContent.first().getValue("text").jsonPrimitive.content)
        assertEquals("done", reply.toolUses.single().name)
        assertEquals(12, reply.inputTokens)
        assertEquals(3, reply.cachedInputTokens)
        assertEquals(7, reply.outputTokens)
    }

    @Test(expected = ApiCallException::class)
    fun completedResponseWithoutOutputIsAnError() {
        client.parseReply("""{"status":"completed","output":[]}""")
    }
}
