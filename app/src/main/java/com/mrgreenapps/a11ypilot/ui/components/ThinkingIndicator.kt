package com.mrgreenapps.a11ypilot.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mrgreenapps.a11ypilot.data.ThinkingState

/**
 * Thinking indicator with an orb large enough to preserve its geometry on phones.
 */
@Composable
fun ThinkingIndicator(
    state: ThinkingState,
    toolName: String? = null,
    modifier: Modifier = Modifier,
    isDark: Boolean? = null,
    step: Int = 0,
    maxSteps: Int = 0
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ThinkingOrb(
            state = state,
            size = 48,
            speed = 1.2f,
            isDark = isDark ?: (MaterialTheme.colorScheme.background.luminance() < 0.5f)
        )
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PulsingStatusDot(color = state.color)
                Text(
                    text = state.getLabel(useZh = true),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                if (maxSteps > 0) {
                    Text(
                        text = "第 $step/$maxSteps 步",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (toolName != null) {
                Text(
                    text = toolName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
            if (maxSteps > 0) {
                StepProgressBar(step = step, maxSteps = maxSteps, color = state.color)
            }
        }
    }
}

/** 多步任务进度条：smooth 动画填充，显示 step/maxSteps 进度。 */
@Composable
private fun StepProgressBar(step: Int, maxSteps: Int, color: androidx.compose.ui.graphics.Color) {
    val target = if (maxSteps <= 0) 0f else (step.toFloat() / maxSteps.toFloat()).coerceIn(0f, 1f)
    val progress by animateFloatAsState(targetValue = target, animationSpec = tween(300), label = "step-progress")
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}

/**
 * Context usage indicator with progress bar
 */
@Composable
fun ContextIndicator(
    currentTokens: Int,
    maxTokens: Int,
    threshold: Int,
    isCompacting: Boolean = false,
    modifier: Modifier = Modifier
) {
    val progress = (currentTokens.toFloat() / maxTokens.toFloat()).coerceIn(0f, 1f)
    val isNearThreshold = currentTokens >= (threshold * 0.9).toInt()

    val indicatorColor = when {
        isCompacting -> MaterialTheme.colorScheme.error
        isNearThreshold -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isCompacting) "压缩中..." else "上下文",
                style = MaterialTheme.typography.labelSmall,
                color = indicatorColor
            )
            Text(
                text = "${currentTokens / 1000}K / ${maxTokens / 1000}K",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }

        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
            color = indicatorColor,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}
