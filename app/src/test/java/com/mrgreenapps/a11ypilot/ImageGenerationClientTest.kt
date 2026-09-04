package com.mrgreenapps.a11ypilot

import com.mrgreenapps.a11ypilot.agent.ImageGenerationClient
import com.mrgreenapps.a11ypilot.data.ImageCapabilities
import com.mrgreenapps.a11ypilot.data.ImageGenerationSettings
import com.mrgreenapps.a11ypilot.data.ImageResolution
import com.mrgreenapps.a11ypilot.data.ImageStyle
import com.mrgreenapps.a11ypilot.data.ImageAspectRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGenerationClientTest {
    private val client = ImageGenerationClient("test-key", "https://example.test")

    @Test
    fun exposesAllFiveAspectRatioOptionsInScreenshotOrder() {
        assertEquals(
            listOf("16:9", "4:3", "1:1", "3:4", "9:16"),
            com.mrgreenapps.a11ypilot.data.ImageAspectRatio.entries.map { it.label }
        )
    }

    @Test
    fun parsesBase64ImageResponse() {
        val payload = client.parseImagePayload("""{"data":[{"b64_json":"abc"}]}""")
        assertEquals("abc", payload.base64)
    }

    @Test
    fun parsesUrlImageResponse() {
        val payload = client.parseImagePayload("""{"data":[{"url":"https://example.test/image.png"}]}""")
        assertEquals("https://example.test/image.png", payload.url)
    }

    @Test
    fun conservativeRequestDoesNotSendUnconfirmedOptions() {
        val body = client.buildRequestBody(
            prompt = "一只猫",
            settings = ImageGenerationSettings(
                aspectRatio = ImageAspectRatio.LANDSCAPE,
                resolution = ImageResolution.TWO_K,
                style = ImageStyle.PHOTOGRAPHY,
                negativePrompt = "文字"
            ),
            capabilities = ImageCapabilities.conservative()
        )
        assertFalse(body.containsKey("size"))
        assertEquals("1", body["n"]?.toString())
        assertNotNull(body["response_format"])
        assertFalse(body.containsKey("quality"))
        assertFalse(body.containsKey("background"))
        assertFalse(body.containsKey("style"))
        assertFalse(body.containsKey("negative_prompt"))
        assertTrue(body["prompt"].toString().contains("风格：摄影"))
        assertTrue(body["prompt"].toString().contains("画幅：16:9"))
        assertTrue(body["prompt"].toString().contains("目标分辨率：2K"))
    }

    @Test
    fun parsesExplicitCapabilitiesAndOnlyAdvertisedFields() {
        val parsed = client.parseCapabilities(
            """{"capabilities":{"sizes":["1024x1024","2048x1152"],"qualities":["standard","high"],"max_n":4,"backgrounds":["transparent"],"supported_parameters":["size","quality","n"]}}"""
        )
        requireNotNull(parsed)
        assertEquals(2, parsed.sizes.size)
        assertEquals(4, parsed.maxCount)
        assertTrue(parsed.supports("quality"))
        assertTrue(parsed.supports("n"))
        assertTrue(parsed.supports("background"))
        assertFalse(parsed.supports("response_format"))
    }
}
