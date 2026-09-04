package com.mrgreenapps.a11ypilot

import com.mrgreenapps.a11ypilot.agent.ApiErrorMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class ApiErrorMessageTest {
    @Test
    fun `403 explains that the server was reached`() {
        val message = ApiErrorMessage.fromHttp("Claude API", 403, """{"error":{"message":"insufficient balance"}}""")
        assertTrue(message.contains("余额"))
        assertTrue(message.contains("HTTP 403"))
        assertTrue(message.contains("insufficient balance"))
        assertFalse(message.contains("{\"error\""))
    }

    @Test
    fun `401 keeps sanitized server detail`() {
        val message = ApiErrorMessage.fromHttp("Claude API", 401, """{"error":{"message":"invalid key"}}""")
        assertTrue(message.contains("API Key"))
        assertTrue(message.contains("invalid key"))
    }

    @Test
    fun `404 identifies responses route and group`() {
        val message = ApiErrorMessage.fromHttp("OpenAI Responses API", 404, """{"message":"group not found"}""")
        assertTrue(message.contains("Responses API"))
        assertTrue(message.contains("OpenAI 分组"))
        assertTrue(message.contains("group not found"))
    }

    @Test
    fun `connection closure points to diagnostics`() {
        assertTrue(ApiErrorMessage.fromThrowable(IOException("connection closed")).contains("网络诊断"))
    }

    @Test
    fun `socket timeout reports a bounded wait`() {
        val message = ApiErrorMessage.fromThrowable(SocketTimeoutException("timeout"))
        assertTrue(message.contains("限定时间"))
        assertTrue(message.contains("中转站使用记录"))
    }
}
