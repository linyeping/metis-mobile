package com.mrgreenapps.a11ypilot.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.mrgreenapps.a11ypilot.data.MessageRole

/**
 * 群组发言人配色：基于 speakerId / userId 的稳定哈希派生 Compose 颜色。
 *
 * 目的：让每个群组成员在群组气泡里有一个稳定的色调，视觉上能瞬间区分不同人。
 * 不持久化颜色值本身（哈希从 id 现算），避免引入冗余字段。
 *
 * 用法：
 *   val bg = speakerBubbleColor(message.speakerId, MaterialTheme.colorScheme)
 *   Surface(color = bg, ...) { ... }
 *
 * 用户消息走 [userBubbleColor]；无 speakerId 的非群组 assistant 走 [neutralBubbleColor]。
 */
object SpeakerColors {
    /**
     * 给定 speakerId 派生一个稳定的 HSL 色调：先把字符串 hash 到 0..359，再转换到
     * Compose Color。亮度/饱和度保持柔和（Material3 surface 风格），不会刺眼。
     */
    fun speakerBubbleColor(speakerId: String, baseOn: Color): Color {
        if (speakerId.isBlank()) return neutralBubbleColor(baseOn)
        val hash = speakerId.fold(0) { acc, c -> (acc * 31 + c.code) and 0x7fffffff }
        val hue = (hash % 360).toFloat()
        val saturation = 0.18f
        val lightness = 0.86f
        val derived = hslToColor(hue, saturation, lightness)
        // 把派生色向 onSurface 方向轻微混色，避免在纯白/纯黑主题下饱和度过高看不清。
        return lerp(derived, baseOn, 0.45f)
    }

    fun userBubbleColor(base: Color): Color = base

    fun neutralBubbleColor(base: Color): Color = base.copy(alpha = 0.65f)

    private fun hslToColor(h: Float, s: Float, l: Float): Color {
        val c = (1 - kotlin.math.abs(2 * l - 1)) * s
        val x = c * (1 - kotlin.math.abs(((h / 60f) % 2) - 1))
        val m = l - c / 2f
        val (r, g, b) = when {
            h < 60 -> Triple(c, x, 0f)
            h < 120 -> Triple(x, c, 0f)
            h < 180 -> Triple(0f, c, x)
            h < 240 -> Triple(0f, x, c)
            h < 300 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return Color(
            red = (r + m).coerceIn(0f, 1f),
            green = (g + m).coerceIn(0f, 1f),
            blue = (b + m).coerceIn(0f, 1f),
            alpha = 1f
        )
    }

    /**
     * 文本颜色：把派生背景反推前景（深色背景用 onSurface，浅色背景用 onSurfaceVariant）。
     */
    fun speakerOnColor(speakerId: String, baseOn: Color): Color {
        if (speakerId.isBlank()) return baseOn
        // 我们的派生色都是浅色 (lightness=0.86)，所以前景沿用 baseOn 即可。
        return baseOn
    }

    @Suppress("unused")
    fun isUserRole(role: MessageRole): Boolean = role == MessageRole.USER
}