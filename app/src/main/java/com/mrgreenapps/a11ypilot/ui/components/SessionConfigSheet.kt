package com.mrgreenapps.a11ypilot.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.mrgreenapps.a11ypilot.agent.AgentSettings
import com.mrgreenapps.a11ypilot.data.*

enum class SessionConfigSection {
    MODEL,
    SAFETY,
    ALL
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionConfigSheet(
    session: Session,
    onDismiss: () -> Unit,
    onSave: (ModelProvider, String, ReasoningIntensity, SafetyLevel) -> Unit,
    section: SessionConfigSection = SessionConfigSection.ALL
) {
    val context = LocalContext.current
    val persistedDeepSeekModels by AgentSettings.deepseekModels(context).collectAsState(initial = emptyList())
    val persistedGptModels by AgentSettings.gptModels(context).collectAsState(initial = emptyList())
    var provider by remember(session.id) { mutableStateOf(session.provider) }
    var model by remember(session.id) { mutableStateOf(session.model) }
    var reasoning by remember(session.id) { mutableStateOf(session.reasoningIntensity) }
    var safety by remember(session.id) { mutableStateOf(session.safetyLevel) }
    var modelMenuOpen by remember { mutableStateOf(false) }
    var expandedSafety by remember { mutableStateOf<SafetyLevel?>(safety) }
    val reasoningOptions = remember(provider, model) { ReasoningCatalog.forModel(provider, model) }

    val availableModels = remember(provider, persistedDeepSeekModels, persistedGptModels) {
        if (provider == ModelProvider.CUSTOM_OPENAI && persistedGptModels.isNotEmpty()) {
            (ModelCatalog.forProvider(provider) + persistedGptModels).distinct().sorted()
        } else if (provider == ModelProvider.DEEPSEEK) {
            ModelCatalog.normalizeDeepSeekModels(persistedDeepSeekModels)
        } else ModelCatalog.forProvider(provider)
    }

    LaunchedEffect(provider, availableModels) {
        if (model !in availableModels) model = availableModels.firstOrNull() ?: ModelCatalog.defaultFor(provider)
    }
    LaunchedEffect(provider, model) {
        reasoning = ReasoningCatalog.normalize(provider, model, reasoning)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Let short model/safety pages hug their content. The sheet will still receive
                // the parent's max height and scroll when a model catalog or custom list grows.
                .wrapContentHeight()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (section != SessionConfigSection.SAFETY) {
                Text("模型提供商", style = MaterialTheme.typography.labelMedium)
                ModelProvider.entries.filter { it != ModelProvider.CUSTOM_CLAUDE }.forEach { item ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 34.dp).clickable { provider = item },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(item.displayName, style = MaterialTheme.typography.bodyMedium)
                        RadioButton(selected = provider == item, onClick = { provider = item })
                    }
                }

                ExposedDropdownMenuBox(expanded = modelMenuOpen, onExpandedChange = { modelMenuOpen = !modelMenuOpen }) {
                    OutlinedTextField(
                        value = model,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("模型") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modelMenuOpen) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(expanded = modelMenuOpen, onDismissRequest = { modelMenuOpen = false }) {
                        availableModels.forEach { item ->
                            DropdownMenuItem(
                                text = { Text(item, maxLines = 1) },
                                onClick = { model = item; modelMenuOpen = false }
                            )
                        }
                    }
                }

                Text("推理强度", style = MaterialTheme.typography.labelMedium)
                if (reasoningOptions.isEmpty()) {
                    Text(
                        "由当前模型决定",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        reasoningOptions.forEachIndexed { index, item ->
                            SegmentedButton(
                                selected = reasoning == item,
                                onClick = { reasoning = item },
                                shape = SegmentedButtonDefaults.itemShape(index, reasoningOptions.size),
                                label = { Text(item.apiValue) }
                            )
                        }
                    }
                }
            }

            if (section != SessionConfigSection.MODEL) {
                SafetyLevel.entries.forEach { item ->
                    val expanded = expandedSafety == item
                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 42.dp)
                                .clickable {
                                    safety = item
                                    expandedSafety = if (expanded) null else item
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = safety == item, onClick = {
                                safety = item
                                expandedSafety = item
                            })
                            Text(item.displayName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Icon(
                                imageVector = if (expanded) androidx.compose.material.icons.Icons.Default.ExpandLess else androidx.compose.material.icons.Icons.Default.ExpandMore,
                                contentDescription = if (expanded) "收起说明" else "展开说明"
                            )
                        }
                        androidx.compose.animation.AnimatedVisibility(visible = expanded) {
                            Text(
                                item.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 48.dp, end = 8.dp, bottom = 6.dp)
                            )
                        }
                    }
                }
            }

            Button(
                onClick = { onSave(provider, model, reasoning, safety); onDismiss() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("应用到当前会话") }
        }
    }
}
