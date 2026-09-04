package com.mrgreenapps.a11ypilot.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.tween
import com.mrgreenapps.a11ypilot.data.ImageAspectRatio
import com.mrgreenapps.a11ypilot.data.ImageCapabilities
import com.mrgreenapps.a11ypilot.data.ImageGenerationSettings
import com.mrgreenapps.a11ypilot.data.ImageResolution

/** Compact per-session image controls. The prompt remains in the composer above this sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageSettingsSheet(
    initial: ImageGenerationSettings,
    capabilities: ImageCapabilities,
    probing: Boolean,
    onDismiss: () -> Unit,
    onSave: (ImageGenerationSettings) -> Unit,
    onPickReference: () -> Unit
) {
    // Keep the legacy parameters in the public API; advanced server-gated fields are
    // intentionally hidden from this compact flow until the relay advertises them.
    @Suppress("UNUSED_VARIABLE") val ignoredCapabilities = capabilities
    @Suppress("UNUSED_VARIABLE") val ignoredProbing = probing
    @Suppress("UNUSED_VARIABLE") val ignoredReferencePicker = onPickReference
    var draft by remember(initial) {
        mutableStateOf(initial.copy(resolution = initial.resolution.takeUnless { it == ImageResolution.AUTO } ?: ImageResolution.ONE_K))
    }
    val ratios = ImageAspectRatio.entries
    val resolutions = listOf(ImageResolution.ONE_K, ImageResolution.TWO_K, ImageResolution.FOUR_K)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.62f)
                .animateContentSize(animationSpec = tween(180))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Image, contentDescription = null)
                Text("图片设置", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp))
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("取消") }
                Button(onClick = { onSave(draft); onDismiss() }) { Text("保存") }
            }
            Text(
                "当前会话",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            AspectRatioRow(
                ratios = ratios,
                selected = draft.aspectRatio,
                onSelected = { draft = draft.copy(aspectRatio = it) }
            )
            ChoiceRow("分辨率", resolutions.map { it.label }, resolutions.indexOf(draft.resolution).coerceAtLeast(0)) { index ->
                draft = draft.copy(resolution = resolutions[index])
            }
            ChoiceRow("图片数量", (1..4).map(Int::toString), (draft.count - 1).coerceIn(0, 3)) { index ->
                draft = draft.copy(count = index + 1)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun AspectRatioRow(
    ratios: List<ImageAspectRatio>,
    selected: ImageAspectRatio,
    onSelected: (ImageAspectRatio) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("比例", style = MaterialTheme.typography.labelLarge)
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(82.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            val selectedIndex = ratios.indexOf(selected).coerceAtLeast(0)
            val segmentWidth = maxWidth / ratios.size
            val indicatorX by animateDpAsState(
                targetValue = segmentWidth * selectedIndex,
                animationSpec = tween(240),
                label = "ratio-indicator"
            )
            Box(
                modifier = Modifier
                    .offset(x = indicatorX)
                    .padding(3.dp)
                    .width(segmentWidth - 6.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
            )
            Row(Modifier.fillMaxSize()) {
                ratios.forEach { ratio ->
                    val isSelected = ratio == selected
                    val contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable { onSelected(ratio) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        RatioGlyph(ratio = ratio, color = contentColor)
                        Spacer(Modifier.height(6.dp))
                        Text(ratio.label, style = MaterialTheme.typography.labelMedium, color = contentColor, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun AspectRatioOption(
    ratio: ImageAspectRatio,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = tween(180),
        label = "ratio-container"
    )
    val contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = modifier.height(76.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = containerColor,
        tonalElevation = if (selected) 2.dp else 0.dp
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            RatioGlyph(ratio = ratio, color = contentColor)
            Text(ratio.label, style = MaterialTheme.typography.labelMedium, color = contentColor, maxLines = 1)
        }
    }
}

@Composable
private fun RatioGlyph(ratio: ImageAspectRatio, color: Color) {
    val (width, height) = when (ratio) {
        ImageAspectRatio.LANDSCAPE -> 28.dp to 16.dp
        ImageAspectRatio.WIDE -> 25.dp to 19.dp
        ImageAspectRatio.SQUARE -> 21.dp to 21.dp
        ImageAspectRatio.PORTRAIT -> 19.dp to 25.dp
        ImageAspectRatio.TALL -> 16.dp to 28.dp
    }
    Box(
        modifier = Modifier
            .size(width = width, height = height)
            .border(width = 2.dp, color = color, shape = RoundedCornerShape(3.dp))
    )
}

@Composable
private fun ChoiceRow(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            val segmentWidth = maxWidth / options.size
            val indicatorX by animateDpAsState(
                targetValue = segmentWidth * selectedIndex,
                animationSpec = tween(240),
                label = "choice-indicator"
            )
            Box(
                modifier = Modifier
                    .offset(x = indicatorX)
                    .padding(3.dp)
                    .width(segmentWidth - 6.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
            )
            Row(Modifier.fillMaxSize()) {
                options.forEachIndexed { index, label ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable { onSelected(index) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (index == selectedIndex) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
