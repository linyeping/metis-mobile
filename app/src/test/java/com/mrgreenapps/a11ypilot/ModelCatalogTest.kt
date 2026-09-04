package com.mrgreenapps.a11ypilot

import com.mrgreenapps.a11ypilot.data.ModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ModelCatalogTest {
    @Test
    fun deepSeekProbeDoesNotExposeRetiredAliases() {
        val models = ModelCatalog.normalizeDeepSeekModels(
            listOf("deepseek-chat", "deepseek-v3", "deepseek-v4-pro", " deepseek-v4-pro ")
        )

        assertFalse(models.any { it.equals("deepseek-chat", ignoreCase = true) })
        assertFalse(models.any { it.startsWith("deepseek-v3", ignoreCase = true) })
        assertEquals(listOf("deepseek-v4-flash", "deepseek-v4-pro"), models)
    }
}
