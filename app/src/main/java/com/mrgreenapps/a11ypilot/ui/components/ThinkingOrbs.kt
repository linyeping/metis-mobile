package com.mrgreenapps.a11ypilot.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import com.mrgreenapps.a11ypilot.data.ThinkingState
import androidx.compose.material3.MaterialTheme

@Composable
fun ThinkingOrb(
    state: ThinkingState,
    size: Int = 20,
    speed: Float = 1f,
    isDark: Boolean? = null,
    modifier: Modifier = Modifier
) {
    var frameNanos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(speed) {
        while (true) withFrameNanos { frameNanos = it }
    }
    val timeSeconds = frameNanos / 1_000_000_000f * speed
    val resolvedDark = isDark ?: (MaterialTheme.colorScheme.background.luminance() < 0.5f)
    Canvas(
        modifier = modifier
            .size(size.dp)
            .semantics { contentDescription = state.labelZh }
    ) {
        val frame = ThinkingOrbEngine.frame(
            state = state,
            size = this.size.minDimension,
            timeSeconds = timeSeconds,
            compact = size <= 28
        )
        frame.dots.fastForEach { dot ->
            val ink = (if (resolvedDark) 1f - dot.white else dot.white).coerceIn(0f, 1f)
            drawCircle(
                color = Color(ink, ink, ink, dot.alpha.coerceIn(0f, 1f)),
                radius = dot.radius,
                center = Offset(dot.x, dot.y)
            )
        }
    }
}

@Composable
fun ThinkingOrbs(
    modifier: Modifier = Modifier,
    isThinking: Boolean = true,
    orbColor: Color = Color.Unspecified,
    glowColor: Color = Color.Unspecified
) {
    if (isThinking) ThinkingOrb(ThinkingState.WORKING, size = 64, modifier = modifier)
}
