package com.mrgreenapps.a11ypilot.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.io.File

/**
 * 工作区底部的输入栏（文本框 + 附件 + 语音 + 发送 / 停止 + 会话配置 chip）。
 *
 * 从 WorkScreen 抽出来之后，原来 700+ 行的 Composable 变成了两层：
 *  1. WorkScreen 负责消息列表 / 抽屉 / 配置面板；
 *  2. MessageComposer 负责「输入 → 触发动作」的最后一公里。
 *
 * 数据流：父组件持有 inputText / imagePromptMode / attachments 这些状态，composer 只负责
 * 渲染与触发回调。这样父组件可以决定「编辑已有消息」时如何重置 composer。
 *
 * 设计原则：父组件负责「会话元数据 → 字符串」的翻译，composer 只接收字符串
 * 与可空回调。这样 composer 不依赖 WorkMode / Session / ImageGenerationSettings
 * 等任何上层模型，可以在没有会话时也安全渲染（例如首次启动）。
 *
 * @param onSendMessage (text, attachments) → 把当前文本与附件当作新消息发出。
 * @param onEditAndResendMessage (messageId, newText) → 用户在「编辑」模式下重发上一条。
 * @param onGenerateImage prompt → 进入图片生成模式后用户点发送。
 * @param onCancel → 任务正在跑时按钮变成「停止」。
 * @param onPickGallery / onTakePhoto / onPickFile → 附件菜单里的三种入口。
 * @param onRemoveAttachment path → 取消某张已选附件。
 * @param onOpenAttachment path → 单击已选图片附件进行预览。
 * @param startVoiceInput → 触发语音识别；权限已由父组件统一处理。
 */
@Composable
fun MessageComposer(
    inputText: String,
    onInputTextChange: (String) -> Unit,
    placeholderText: String,
    imagePromptMode: Boolean,
    onExitImagePromptMode: () -> Unit,
    attachments: List<String>,
    onRemoveAttachment: (String) -> Unit,
    onOpenAttachment: (String) -> Unit,
    voiceError: String?,
    isListening: Boolean,
    isRunning: Boolean,
    isImageGenerating: Boolean,
    editingMessageId: String?,
    onCancelEdit: () -> Unit,
    modelLabel: String?,
    onShowModelConfig: (() -> Unit)?,
    safetyLabel: String?,
    onShowSafetyConfig: (() -> Unit)?,
    imageSettingsLabel: String? = null,
    onShowImageSettings: (() -> Unit)? = null,
    showAttachmentSheet: Boolean,
    onShowAttachmentSheet: (Boolean) -> Unit,
    onPickGallery: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickFile: () -> Unit,
    onSendMessage: (String, List<String>) -> Unit,
    onEditAndResendMessage: (String, String) -> Unit,
    onGenerateImage: (String) -> Unit,
    onCancel: () -> Unit,
    startVoiceInput: () -> Unit,
    onHeightChange: (Int) -> Unit = {},
) {
    val composerControlSize = 36.dp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 760.dp)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .onSizeChanged { onHeightChange(it.height) }
            .animateContentSize(animationSpec = tween(220)),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        AnimatedVisibility(
            visible = imagePromptMode,
            enter = fadeIn(tween(160)) + slideInVertically(tween(220)) { -it / 2 },
            exit = fadeOut(tween(120))
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "图片生成 · GPT Image 2",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                IconButton(
                    onClick = onExitImagePromptMode,
                    modifier = Modifier.size(28.dp)
                ) { Icon(Icons.Default.Close, "退出图片生成", modifier = Modifier.size(18.dp)) }
            }
        }
        if (attachments.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                attachments.take(3).forEach { path ->
                    val file = File(path)
                    if (isImageFile(file)) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .clickable { onOpenAttachment(path) }
                        ) {
                            AsyncImage(
                                model = file,
                                contentDescription = file.name,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                            Surface(
                                modifier = Modifier.align(Alignment.TopEnd).size(24.dp),
                                shape = RoundedCornerShape(bottomStart = 8.dp),
                                color = Color.Black.copy(alpha = 0.56f)
                            ) {
                                IconButton(
                                    onClick = { onRemoveAttachment(path) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        "移除图片",
                                        modifier = Modifier.size(14.dp),
                                        tint = Color.White
                                    )
                                }
                            }
                        }
                    } else {
                        Surface(
                            modifier = Modifier.widthIn(max = 190.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 8.dp, end = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = file.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { onRemoveAttachment(path) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        "移除附件",
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                if (attachments.size > 3) {
                    Text(
                        "+${attachments.size - 3}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                }
            }
        }

        // The composer follows the mobile layout: text first, controls second.
        voiceError?.let { err ->
            Text(
                err,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
        BasicTextField(
            value = inputText,
            onValueChange = onInputTextChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp, max = 116.dp)
                .animateContentSize(animationSpec = tween(180))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            singleLine = false,
            maxLines = 6,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { innerTextField ->
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopStart) {
                    if (inputText.isBlank()) {
                        Text(
                            if (imagePromptMode) "描述要生成的图片" else placeholderText,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    innerTextField()
                }
            }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilledTonalIconButton(
                onClick = { onShowAttachmentSheet(true) },
                modifier = Modifier.size(composerControlSize)
            ) { Icon(Icons.Default.Add, "添加附件或生成图片", modifier = Modifier.size(20.dp)) }

            if (modelLabel != null && onShowModelConfig != null) {
                AssistChip(
                    onClick = onShowModelConfig,
                    label = {
                        Text(
                            text = modelLabel,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(30.dp)
                )
            }
            if (safetyLabel != null && onShowSafetyConfig != null) {
                AssistChip(
                    onClick = onShowSafetyConfig,
                    label = {
                        Text(
                            text = safetyLabel,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    modifier = Modifier.height(30.dp)
                )
            }
            if (imageSettingsLabel != null && onShowImageSettings != null) {
                AssistChip(
                    onClick = onShowImageSettings,
                    label = {
                        Text(
                            imageSettingsLabel,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    modifier = Modifier.height(30.dp)
                )
            }
            if (editingMessageId != null) {
                TextButton(
                    onClick = onCancelEdit,
                    modifier = Modifier.height(30.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp)
                ) { Text("取消编辑", style = MaterialTheme.typography.labelSmall) }
            }
            Spacer(Modifier.weight(1f))
            if (!isRunning) {
                FilledTonalIconButton(
                    onClick = startVoiceInput,
                    modifier = Modifier.size(composerControlSize)
                ) {
                    if (isListening) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(Icons.Default.Mic, "语音输入", modifier = Modifier.size(20.dp))
                    }
                }
            }
            FilledIconButton(
                onClick = {
                    if (isRunning) {
                        onCancel()
                    } else if (imagePromptMode && inputText.isNotBlank()) {
                        onGenerateImage(inputText.trim())
                        onInputTextChange("")
                        onExitImagePromptMode()
                    } else if (inputText.isNotBlank() || attachments.isNotEmpty()) {
                        val editedId = editingMessageId
                        if (editedId != null) {
                            onEditAndResendMessage(editedId, inputText.trim())
                            onCancelEdit()
                        } else {
                            onSendMessage(inputText.trim(), attachments)
                        }
                        onInputTextChange("")
                    }
                },
                enabled = isRunning ||
                    (!isImageGenerating && (inputText.isNotBlank() || attachments.isNotEmpty())),
                modifier = Modifier.size(composerControlSize)
            ) {
                if (isRunning) {
                    Icon(Icons.Default.Stop, "停止", modifier = Modifier.size(19.dp))
                } else if (isImageGenerating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(Icons.AutoMirrored.Filled.Send, "发送", modifier = Modifier.size(19.dp))
                }
            }
        }
    }
}

private fun isImageFile(file: File): Boolean {
    val name = file.name.lowercase()
    return name.endsWith(".jpg") || name.endsWith(".jpeg") ||
        name.endsWith(".png") || name.endsWith(".gif") ||
        name.endsWith(".webp") || name.endsWith(".bmp")
}
