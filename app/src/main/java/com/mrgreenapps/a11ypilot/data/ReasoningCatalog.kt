package com.mrgreenapps.a11ypilot.data

object ReasoningCatalog {
    private val claudeEffortModels = listOf(
        Regex("^claude-(sonnet|opus)-4-[6-9](?:-|$)"),
        Regex("^claude-(sonnet|opus)-[5-9](?:-|$)"),
        Regex("^claude-fable-[5-9](?:-|$)")
    )

    fun forModel(provider: ModelProvider, model: String): List<ReasoningIntensity> = when (provider) {
        ModelProvider.CUSTOM_CLAUDE -> if (claudeEffortModels.any { it.containsMatchIn(model) }) {
            listOf(
                ReasoningIntensity.LOW,
                ReasoningIntensity.MEDIUM,
                ReasoningIntensity.HIGH,
                ReasoningIntensity.MAX
            )
        } else {
            emptyList()
        }
        ModelProvider.CUSTOM_OPENAI -> listOf(
            ReasoningIntensity.LOW,
            ReasoningIntensity.MEDIUM,
            ReasoningIntensity.HIGH,
            ReasoningIntensity.XHIGH
        )
        ModelProvider.DEEPSEEK -> emptyList()
    }

    fun defaultFor(provider: ModelProvider, model: String): ReasoningIntensity = when (provider) {
        ModelProvider.CUSTOM_OPENAI ->
            if (model == "gpt-5.6-terra") ReasoningIntensity.HIGH else ReasoningIntensity.XHIGH
        else -> forModel(provider, model).let { supported ->
            ReasoningIntensity.MEDIUM.takeIf { it in supported }
                ?: supported.firstOrNull()
                ?: ReasoningIntensity.MEDIUM
        }
    }

    fun normalize(
        provider: ModelProvider,
        model: String,
        value: ReasoningIntensity
    ): ReasoningIntensity {
        val supported = forModel(provider, model)
        return value.takeIf { it in supported } ?: defaultFor(provider, model)
    }
}
