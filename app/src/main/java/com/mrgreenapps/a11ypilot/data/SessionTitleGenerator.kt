package com.mrgreenapps.a11ypilot.data

object SessionTitleGenerator {
    fun generate(content: String, mode: WorkMode): String {
        val cleaned = content
            .replace(Regex("```[\\s\\S]*?```"), "代码任务")
            .replace(Regex("[#>*_`\\[\\]()]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleaned.isBlank()) return defaultTitle(mode)
        val limit = 22
        return if (cleaned.length <= limit) cleaned else cleaned.take(limit).trimEnd() + "..."
    }

    fun isDefault(title: String, mode: WorkMode): Boolean =
        title == "新对话" || title.startsWith("${mode.titleZh()}会话")

    private fun defaultTitle(mode: WorkMode): String = "${mode.titleZh()}会话"

    private fun WorkMode.titleZh(): String = when (this) {
        WorkMode.CHAT -> "聊天"
        WorkMode.COWORK -> "协作"
        WorkMode.CODE -> "编程"
    }
}
