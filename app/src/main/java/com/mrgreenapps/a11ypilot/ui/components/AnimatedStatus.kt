package com.mrgreenapps.a11ypilot.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.BasicText

/**
 * 一组基于 Compose 原生动画（infiniteTransition + animateFloat）的动态状态组件。
 * 全部在渲染线程运行、零额外依赖、不触发布局重排，因此比引入 Lottie 更丝滑、更低功耗。
 * 思考球（ThinkingOrb）保持原样，这些组件用于状态标签、工具轨迹等辅助指示。
 */

/** 呼吸圆点：颜色按 [color] 做 scale + alpha 脉动，用于「进行中」状态。 */
@Composable
fun PulsingStatusDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: Int = 8
) {
    val transition = rememberInfiniteTransition(label = "status-dot")
    val pulse by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "status-dot-pulse"
    )
    Canvas(modifier = modifier.size(size.dp)) {
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val maxRadius = this.size.minDimension / 2f
        // 外圈光晕
        drawCircle(
            color = color.copy(alpha = 0.25f * pulse),
            radius = maxRadius
        )
        // 实心点
        drawCircle(
            color = color.copy(alpha = 0.35f + 0.65f * pulse),
            radius = maxRadius * (0.45f + 0.25f * pulse)
        )
    }
}

/** 流动光晕文本：一段文字上叠加从左到右循环流动的高亮，用于「进行中」的工具名/提示。 */
@Composable
fun ShimmerText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle,
    baseColor: Color,
    highlightColor: Color
) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val shift by transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer-shift"
    )
    val brush = Brush.linearGradient(
        colors = listOf(baseColor, highlightColor, baseColor),
        start = Offset(shift * 200f, 0f),
        end = Offset(shift * 200f + 200f, 0f)
    )
    BasicText(
        text = AnnotatedString(text),
        modifier = modifier,
        style = style.copy(brush = brush)
    )
}
