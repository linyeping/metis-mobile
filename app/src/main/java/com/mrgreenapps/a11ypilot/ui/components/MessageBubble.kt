package com.mrgreenapps.a11ypilot.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.mrgreenapps.a11ypilot.tools.ToolNames
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mrgreenapps.a11ypilot.agent.AgentEngine
import com.mrgreenapps.a11ypilot.agent.CharacterCard
import com.mrgreenapps.a11ypilot.data.Message
import com.mrgreenapps.a11ypilot.data.MessageRole
import com.mrgreenapps.a11ypilot.data.MessageStatus
import com.mrgreenapps.a11ypilot.data.ThinkingState
import com.mrgreenapps.a11ypilot.ui.SpeakerColors
import com.mrgreenapps.a11ypilot.ui.theme.FiraCodeFamily
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.File
import coil.compose.AsyncImage

@Composable
fun MessageBubble(
    message: Message,
    liveAgentState: AgentEngine.State? = null,
    modifier: Modifier = Modifier,
    onOpenFile: (String) -> Unit = {},
    isLatestUserMessage: Boolean = false,
    onEdit: (Message) -> Unit = {},
    onCopy: (Message) -> Unit = {},
    onShare: (Message) -> Unit = {},
    onRegenerate: (Message) -> Unit = {},
    /**
     * 群组模式下显示在气泡上方的发言人名。普通对话始终为 null。
     */
    speakerName: String? = null,
    /**
     * 群组模式下发言人头像 URI；为空时使用 CharacterAvatar 的"首字母"回退样式。
     */
    speakerAvatarUri: String? = null,
    /**
     * 当前气泡是否为该发言人连续气泡中的首条；决定是否显示头像 + 名字头，
     * 并影响气泡上角圆滑度。
     */
    isFirstInSpeakerGroup: Boolean = true,
    /**
     * 当前气泡是否为该发言人连续气泡中的末条；决定气泡下角圆滑度。
     */
    isLastInSpeakerGroup: Boolean = true,
    /**
     * 单条发言（独立成组）时仍按 12.dp 圆角渲染；中间夹条下下都贴边。
     */
    speakBubbleCornerDp: androidx.compose.ui.unit.Dp = 12.dp
) {
    val isUser = message.role == MessageRole.USER
    val isGroupMessage = message.speakerId != null
    val liveRunning = liveAgentState as? AgentEngine.State.Running
    val thinkingState = liveRunning?.thinkingState ?: message.thinkingState ?: ThinkingState.UNDERSTANDING
    val toolName = liveRunning?.last ?: message.toolCalls?.lastOrNull()?.name

    if (!isUser && message.status == MessageStatus.IN_PROGRESS) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 群组气泡在 IN_PROGRESS 时附带头像 + 名字，让「X 正在思考…」与发言人关联。
            if (isGroupMessage && isFirstInSpeakerGroup) {
                Box(modifier = Modifier.padding(top = if (speakerName.isNullOrBlank()) 0.dp else 16.dp)) {
                    CharacterAvatar(
                        card = CharacterCard(
                            id = message.speakerId.orEmpty(),
                            name = speakerName.orEmpty().ifBlank { "群成员" },
                            description = "",
                            avatarUri = speakerAvatarUri.orEmpty()
                        ),
                        size = 32.dp,
                        corner = 8.dp
                    )
                }
                Spacer(Modifier.width(8.dp))
            }
            ThinkingIndicator(
                state = thinkingState,
                toolName = toolName?.let(::toolLabelZh),
                step = liveRunning?.step ?: 0,
                maxSteps = liveRunning?.maxSteps ?: 0
            )
        }
        return
    }

    val dateFormat = remember { SimpleDateFormat("HH:mm", Locale.SIMPLIFIED_CHINESE) }
    val maxBubbleWidth = minOf(LocalConfiguration.current.screenWidthDp.dp * 0.82f, 600.dp)
    var traceExpanded by remember(message.id) { mutableStateOf(false) }
    var actionsExpanded by remember(message.id) { mutableStateOf(false) }

    // 群聊气泡按"是否连续同发言人"调整四个角的圆角，让视觉上像 iMessage / Telegram
    // 的群成员对话样式拼接。普通对话维持单一 8.dp 圆角，跟原版观感一致。
    val bubbleShape = if (isGroupMessage) {
        when {
            isFirstInSpeakerGroup && isLastInSpeakerGroup ->
                RoundedCornerShape(speakBubbleCornerDp)
            isFirstInSpeakerGroup ->
                RoundedCornerShape(
                    topStart = speakBubbleCornerDp,
                    topEnd = speakBubbleCornerDp,
                    bottomStart = 4.dp,
                    bottomEnd = 4.dp
                )
            isLastInSpeakerGroup ->
                RoundedCornerShape(
                    topStart = 4.dp,
                    topEnd = 4.dp,
                    bottomStart = speakBubbleCornerDp,
                    bottomEnd = speakBubbleCornerDp
                )
            else -> RoundedCornerShape(4.dp)
        }
    } else {
        RoundedCornerShape(8.dp)
    }

    // 群组气泡的背景色：每位成员一个稳定色调。
    val speakerBubble = if (isGroupMessage) {
        SpeakerColors.speakerBubbleColor(
            message.speakerId.orEmpty(),
            MaterialTheme.colorScheme.secondaryContainer
        )
    } else null

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        // 群组模式首条气泡：在气泡左侧渲染一个小头像，与气泡顶部对齐。
        if (!isUser && isGroupMessage && isFirstInSpeakerGroup) {
            Box(
                modifier = Modifier
                    .padding(top = if (speakerName.isNullOrBlank()) 6.dp else 22.dp)
            ) {
                CharacterAvatar(
                    card = CharacterCard(
                        id = message.speakerId.orEmpty(),
                        name = speakerName.orEmpty().ifBlank { "群成员" },
                        description = "",
                        avatarUri = speakerAvatarUri.orEmpty()
                    ),
                    size = 32.dp,
                    corner = 8.dp
                )
            }
            Spacer(Modifier.width(8.dp))
        }
        Column(
            modifier = if (isUser) {
                Modifier.widthIn(max = maxBubbleWidth)
            } else {
                Modifier.fillMaxWidth().widthIn(max = maxBubbleWidth)
            },
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            // 群组模式首条气泡：在气泡上方显示发言人名字，提升可识别度。
            if (!isUser && isGroupMessage && isFirstInSpeakerGroup && !speakerName.isNullOrBlank()) {
                Text(
                    text = speakerName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    modifier = Modifier.padding(start = 2.dp, bottom = 2.dp)
                )
            }
            if (!isUser && !message.toolCalls.isNullOrEmpty()) {
                ToolTrace(
                    calls = message.toolCalls.orEmpty(),
                    expanded = traceExpanded,
                    onToggle = { traceExpanded = !traceExpanded }
                )
            }
            Surface(
                modifier = Modifier
                    .then(if (isUser) Modifier.align(Alignment.End) else Modifier)
                    .then(if (!isUser) Modifier.fillMaxWidth() else Modifier)
                    .then(if (isUser) Modifier.clickable { actionsExpanded = !actionsExpanded } else Modifier),
                color = when {
                    message.status == MessageStatus.ERROR -> MaterialTheme.colorScheme.errorContainer
                    isUser -> MaterialTheme.colorScheme.primaryContainer
                    isGroupMessage -> speakerBubble ?: MaterialTheme.colorScheme.secondaryContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
                shape = bubbleShape
            ) {
                Column(
                    modifier = Modifier.padding(
                        horizontal = if (isUser) 12.dp else 8.dp,
                        vertical = 10.dp
                    )
                ) {
                    if (!isUser) {
                        Icon(
                            imageVector = when (message.status) {
                                MessageStatus.ERROR -> Icons.Outlined.ErrorOutline
                                else -> Icons.Outlined.CheckCircle
                            },
                            contentDescription = if (message.status == MessageStatus.ERROR) "任务失败" else "任务完成",
                            modifier = Modifier.size(17.dp),
                            tint = if (message.status == MessageStatus.ERROR) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    if (message.content.isNotBlank()) {
                        if (isUser) {
                            Text(
                                text = message.content,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        } else {
                            Markdown(
                                content = message.content,
                                colors = markdownColor(
                                    text = MaterialTheme.colorScheme.onSurfaceVariant,
                                    codeText = MaterialTheme.colorScheme.onSurfaceVariant,
                                    codeBackground = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                                    linkText = MaterialTheme.colorScheme.primary
                                ),
                                typography = markdownTypography(
                                    code = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = FiraCodeFamily ?: FontFamily.Monospace
                                    )
                                )
                            )
                        }
                    }

                    message.attachments.orEmpty().forEach { filePath ->
                        Spacer(Modifier.height(8.dp))
                        if (File(filePath).extension.lowercase(Locale.ROOT) in IMAGE_EXTENSIONS && File(filePath).isFile) {
                            AsyncImage(
                                model = File(filePath),
                                contentDescription = File(filePath).name,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 360.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onOpenFile(filePath) },
                                contentScale = androidx.compose.ui.layout.ContentScale.Fit
                            )
                        } else {
                            FileAttachment(
                                filePath = filePath,
                                onPreview = { onOpenFile(filePath) }
                            )
                        }
                    }

                }
            }
            val showActions = if (isUser) actionsExpanded else message.status != MessageStatus.IN_PROGRESS
            if (showActions) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 2.dp),
                    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                ) {
                    IconButton(onClick = { onCopy(message) }, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.ContentCopy, "复制消息", modifier = Modifier.size(17.dp))
                    }
                    if (isUser && isLatestUserMessage) {
                        IconButton(onClick = { onEdit(message) }, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Default.Edit, "编辑消息", modifier = Modifier.size(17.dp))
                        }
                    }
                    if (!isUser) {
                        IconButton(onClick = { onShare(message) }, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Default.Share, "分享消息", modifier = Modifier.size(17.dp))
                        }
                        // 群组消息：regenerate 只会重跑当前发言人（不破坏其它成员气泡）。
                        IconButton(onClick = { onRegenerate(message) }, modifier = Modifier.size(30.dp)) {
                            Icon(
                                if (isGroupMessage) Icons.Default.Refresh else Icons.Default.Refresh,
                                if (isGroupMessage) "重新生成此成员回答" else "重新生成",
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                }
            }
            Text(
                text = dateFormat.format(Date(message.timestamp)),
                style = MaterialTheme.typography.labelSmall,
                textAlign = if (isUser) TextAlign.End else TextAlign.Start,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.65f)
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
            )
        }
    }
}

@Composable
private fun ToolTrace(
    calls: List<com.mrgreenapps.a11ypilot.data.ToolCall>,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(start = 2.dp, bottom = 3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 3.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Build, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(7.dp))
            Text(
                text = "${calls.size} 个工具调用",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Icon(
                if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                if (expanded) "收起工具调用" else "展开工具调用",
                Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (expanded) {
            val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.SIMPLIFIED_CHINESE) }
            calls.forEach { call ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 23.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = toolIcon(call.name),
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        text = toolLabelZh(call.name),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = timeFormat.format(Date(call.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    Spacer(Modifier.size(6.dp))
                    if (call.status != "done") {
                        PulsingStatusDot(
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(10.dp)
                        )
                        Spacer(Modifier.size(4.dp))
                    }
                    Text(
                        text = if (call.status == "done") "完成" else "进行中",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun toolLabelZh(toolName: String): String = when (toolName.substringBefore('(')) {
    ToolNames.DUMP_SCREEN -> "读取当前屏幕"
    ToolNames.SCREENSHOT -> "分析屏幕画面"
    ToolNames.CLICK, ToolNames.TAP, ToolNames.LONG_CLICK, ToolNames.SWIPE, ToolNames.SCROLL, ToolNames.SET_TEXT -> "操作手机界面"
    ToolNames.LAUNCH_APP, ToolNames.OPEN_BILIBILI_SEARCH, ToolNames.SHARE_BILIBILI_TO_WECHAT -> "打开手机应用"
    ToolNames.SET_ALARM -> "设置闹钟"
    ToolNames.READ_FILE, ToolNames.LIST_FILES -> "读取文件"
    ToolNames.WRITE_FILE -> "写入文件"
    ToolNames.RUN_COMMAND -> "运行代码"
    else -> toolName
}

private fun toolIcon(toolName: String) = when (toolName.substringBefore('(')) {
    ToolNames.DUMP_SCREEN -> Icons.Default.PhoneAndroid
    ToolNames.SCREENSHOT -> Icons.Default.Search
    ToolNames.CLICK, ToolNames.TAP, ToolNames.LONG_CLICK, ToolNames.SWIPE, ToolNames.SCROLL, ToolNames.SET_TEXT, ToolNames.GLOBAL -> Icons.Default.TouchApp
    ToolNames.LAUNCH_APP -> Icons.Default.OpenInNew
    ToolNames.OPEN_BILIBILI_SEARCH, ToolNames.GREP, ToolNames.GLOB -> Icons.Default.Search
    ToolNames.SHARE_BILIBILI_TO_WECHAT -> Icons.Default.Share
    ToolNames.WEB_SEARCH -> Icons.Default.Language
    ToolNames.SET_ALARM -> Icons.Default.Alarm
    ToolNames.READ_FILE, ToolNames.LIST_FILES -> Icons.Default.Description
    ToolNames.WRITE_FILE, ToolNames.NOTEBOOK_EDIT -> Icons.Default.Code
    ToolNames.RUN_COMMAND, ToolNames.GIT -> Icons.Default.Terminal
    ToolNames.SHARE_FILE -> Icons.Default.Share
    else -> Icons.Default.Language
}

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
