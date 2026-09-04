package com.mrgreenapps.a11ypilot.data

import kotlinx.serialization.Serializable

/** Recurrence choices exposed by the automation editor. */
@Serializable
enum class AutomationTrigger {
    ONCE,
    HOURLY,
    DAILY,
    WEEKDAYS,
    WEEKLY,
    MONTHLY,
    YEARLY
}

@Serializable
data class AutomationTask(
    val id: String,
    val name: String,
    val prompt: String,
    val trigger: AutomationTrigger = AutomationTrigger.DAILY,
    val hour: Int = 9,
    val minute: Int = 0,
    /** java.util.Calendar day constants, where Sunday is 1 and Saturday is 7. */
    val dayOfWeek: Int = java.util.Calendar.MONDAY,
    val dayOfMonth: Int = 1,
    val month: Int = 1,
    val mode: WorkMode = WorkMode.COWORK,
    val provider: ModelProvider = ModelProvider.CUSTOM_OPENAI,
    val model: String = "gpt-5.6-terra",
    val reasoningIntensity: ReasoningIntensity = ReasoningIntensity.HIGH,
    val safetyLevel: SafetyLevel = SafetyLevel.BALANCED,
    val enabled: Boolean = true,
    val nextRunAt: Long = 0L,
    val lastRunAt: Long? = null,
    val sessionId: String? = null
)
