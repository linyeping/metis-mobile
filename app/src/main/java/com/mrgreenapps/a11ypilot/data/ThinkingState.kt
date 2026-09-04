package com.mrgreenapps.a11ypilot.data

import androidx.compose.ui.graphics.Color

/**
 * Thinking states for agent operations
 * Based on Metis design: https://github.com/linyeping/Metis
 */
enum class ThinkingState(
    val label: String,
    val labelZh: String,
    val color: Color
) {
    UNDERSTANDING(
        label = "Understanding",
        labelZh = "理解任务",
        color = Color(0xFF6366F1)
    ),
    RETRIEVING(
        label = "Retrieving",
        labelZh = "检索材料",
        color = Color(0xFF3B82F6)
    ),
    EXECUTING(
        label = "Executing",
        labelZh = "执行工具",
        color = Color(0xFFF59E0B)
    ),
    ORGANIZING(
        label = "Organizing",
        labelZh = "组织答案",
        color = Color(0xFF10B981)
    ),
    THINKING(
        label = "Thinking",
        labelZh = "深度思考",
        color = Color(0xFF8B5CF6)
    ),
    WORKING(
        label = "Working",
        labelZh = "处理中",
        color = Color(0xFF6366F1)
    ),
    COMPACTING(
        label = "Compacting",
        labelZh = "压缩上下文",
        color = Color(0xFFEC4899)
    );

    fun getLabel(useZh: Boolean = true): String {
        return if (useZh) labelZh else label
    }
}

/**
 * Context compaction status
 */
data class CompactionStatus(
    val isCompacting: Boolean = false,
    val currentTokens: Int = 0,
    val maxTokens: Int = 372_000,
    val threshold: Int = 316_200,
    val progress: Float = 0f
) {
    val isNearThreshold: Boolean
        get() = currentTokens >= (threshold * 0.9).toInt()

    val percentUsed: Float
        get() = (currentTokens.toFloat() / maxTokens.toFloat()).coerceIn(0f, 1f)

    val shouldCompact: Boolean
        get() = currentTokens >= threshold
}
