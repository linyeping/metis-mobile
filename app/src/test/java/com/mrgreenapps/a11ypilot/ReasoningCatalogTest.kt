package com.mrgreenapps.a11ypilot

import com.mrgreenapps.a11ypilot.data.ModelProvider
import com.mrgreenapps.a11ypilot.data.ReasoningCatalog
import com.mrgreenapps.a11ypilot.data.ReasoningIntensity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningCatalogTest {
    @Test
    fun openAiUsesOfficialReasoningValues() {
        assertEquals(
            listOf("low", "medium", "high", "xhigh"),
            ReasoningCatalog.forModel(ModelProvider.CUSTOM_OPENAI, "gpt-5.5").map { it.apiValue }
        )
        assertEquals(
            ReasoningIntensity.XHIGH,
            ReasoningCatalog.defaultFor(ModelProvider.CUSTOM_OPENAI, "gpt-5.5")
        )
    }

    @Test
    fun supportedClaudeEffortIncludesMax() {
        assertEquals(
            listOf("low", "medium", "high", "max"),
            ReasoningCatalog.forModel(ModelProvider.CUSTOM_CLAUDE, "claude-opus-4-6").map { it.apiValue }
        )
    }

    @Test
    fun legacyClaudeAndDeepSeekDoNotExposeInventedEffort() {
        assertTrue(ReasoningCatalog.forModel(ModelProvider.CUSTOM_CLAUDE, "claude-sonnet-4-20250514").isEmpty())
        assertTrue(ReasoningCatalog.forModel(ModelProvider.DEEPSEEK, "deepseek-reasoner").isEmpty())
    }
}
