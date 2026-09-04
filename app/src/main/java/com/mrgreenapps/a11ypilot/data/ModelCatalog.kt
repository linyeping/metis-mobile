package com.mrgreenapps.a11ypilot.data

object ModelCatalog {
    val claudeModels = listOf(
        "claude-sonnet-4-20250514",
        "claude-sonnet-4-5-20250929",
        "claude-sonnet-4-5-20250929-thinking",
        "claude-sonnet-4-6",
        "claude-sonnet-5",
        "claude-opus-4-20250514",
        "claude-opus-4-1-20250805",
        "claude-opus-4-5-20251101",
        "claude-opus-4-5-20251101-thinking",
        "claude-opus-4-6",
        "claude-opus-4-7",
        "claude-opus-4-8",
        "claude-opus-5",
        "claude-haiku-4-5-20251001",
        "claude-3-7-sonnet-20250219",
        "claude-3-5-sonnet-20241022",
        "claude-3-5-sonnet-20240620",
        "claude-3-5-haiku-20241022",
        "claude-fable-5"
    )

    val openAiModels = listOf(
        "gpt-5.6-sol",
        "gpt-5.6-terra",
        "gpt-5.6-luna",
        "gpt-5.5",
        "gpt-5.4",
        "gpt-5.4-mini",
        "gpt-5.3-codex-spark",
        "gpt-5.2",
        "codex-auto-review"
    )

    // Keep only current-style IDs as a fallback. Settings can replace this list with /v1/models.
    // Official DeepSeek V4 IDs. The live probe can append newer IDs without an app update.
    val deepSeekModels = listOf("deepseek-v4-flash", "deepseek-v4-pro")

    fun forProvider(provider: ModelProvider): List<String> = when (provider) {
        ModelProvider.CUSTOM_CLAUDE -> claudeModels
        ModelProvider.CUSTOM_OPENAI -> openAiModels
        ModelProvider.DEEPSEEK -> deepSeekModels
    }

    fun defaultFor(provider: ModelProvider): String = when (provider) {
        ModelProvider.CUSTOM_CLAUDE -> "claude-sonnet-4-20250514"
        ModelProvider.CUSTOM_OPENAI -> "gpt-5.6-terra"
        ModelProvider.DEEPSEEK -> "deepseek-v4-flash"
    }

    /** Keep stale DeepSeek aliases out of the model picker after a live probe. */
    fun normalizeDeepSeekModels(models: List<String>): List<String> =
        (deepSeekModels + models)
            .map(String::trim)
            .filter(String::isNotBlank)
            .filterNot { it.equals("deepseek-chat", ignoreCase = true) || it.startsWith("deepseek-v3", ignoreCase = true) }
            .distinct()
            .sorted()
}
