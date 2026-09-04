package com.mrgreenapps.a11ypilot.ui.screens

import androidx.compose.runtime.Composable
import com.mrgreenapps.a11ypilot.agent.AgentEngine
import com.mrgreenapps.a11ypilot.data.Message
import com.mrgreenapps.a11ypilot.data.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun ChatScreen(
    mode: WorkMode,
    sessions: List<Session>,
    activeSessionId: String?,
    messages: List<Message>,
    agentState: AgentEngine.State,
    onCreateSession: () -> Unit,
    onSelectSession: (String) -> Unit,
    onRenameSession: (String, String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onTogglePinSession: (String) -> Unit = {},
    onSendMessage: (String, List<String>) -> Unit,
    onEditAndResendMessage: (String, String) -> Unit = { _, _ -> },
    onRegenerateMessage: (String) -> Unit = {},
    onCopyMessage: (String) -> Unit = {},
    onShareMessage: (String) -> Unit = {},
    onCancel: () -> Unit,
    onOpenFile: (String) -> Unit,
    onUpdateSessionConfig: (String, ModelProvider, String, ReasoningIntensity, SafetyLevel) -> Unit,
    composerAttachments: List<String> = emptyList(),
    onPickGallery: () -> Unit = {},
    onTakePhoto: () -> Unit = {},
    onPickFile: () -> Unit = {},
    onGenerateImage: (String) -> Unit = {},
    imageSettings: ImageGenerationSettings = ImageGenerationSettings(),
    imageCapabilities: ImageCapabilities = ImageCapabilities.conservative(),
    imageCapabilitiesLoading: Boolean = false,
    onSaveImageSettings: (ImageGenerationSettings) -> Unit = {},
    onPickImageReference: () -> Unit = {},
    onProbeImageCapabilities: () -> Unit = {},
    onRemoveAttachment: (String) -> Unit = {},
    isImageGenerating: Boolean = false,
    bottomBarInset: Dp = 0.dp,
    onOpenSettings: () -> Unit = {},
    onOpenImages: () -> Unit = {},
    onOpenLibrary: () -> Unit = {},
    onOpenProjects: () -> Unit = {},
    onOpenAutomation: () -> Unit = {},
    onOpenRemote: () -> Unit = {},
    onOpenCharacters: () -> Unit = {},
    /**
     * 触发「导出当前会话为 Markdown 并通过系统分享面板送出」。
     * 实现里把内容写到 cacheDir/exports/<sessionId>-<ts>.md，然后 startActivity(ACTION_SEND)。
     */
    onExportSession: () -> Unit = {}
) = WorkScreen(
    mode, sessions, activeSessionId, messages, agentState, onCreateSession,
    onSelectSession, onRenameSession, onDeleteSession,
    onTogglePinSession = onTogglePinSession,
    onSendMessage = onSendMessage,
    onEditAndResendMessage = onEditAndResendMessage,
    onRegenerateMessage = onRegenerateMessage,
    onCopyMessage = onCopyMessage,
    onShareMessage = onShareMessage,
    onCancel = onCancel,
    onOpenFile = onOpenFile,
    onUpdateSessionConfig = onUpdateSessionConfig,
    composerAttachments = composerAttachments,
    onPickGallery = onPickGallery,
    onTakePhoto = onTakePhoto,
    onPickFile = onPickFile,
    onGenerateImage = onGenerateImage,
    imageSettings = imageSettings,
    imageCapabilities = imageCapabilities,
    imageCapabilitiesLoading = imageCapabilitiesLoading,
    onSaveImageSettings = onSaveImageSettings,
    onPickImageReference = onPickImageReference,
    onProbeImageCapabilities = onProbeImageCapabilities,
    onRemoveAttachment = onRemoveAttachment,
    isImageGenerating = isImageGenerating,
    bottomBarInset = bottomBarInset,
    onOpenSettings = onOpenSettings,
    onOpenImages = onOpenImages,
    onOpenLibrary = onOpenLibrary,
    onOpenProjects = onOpenProjects,
    onOpenAutomation = onOpenAutomation,
    onOpenRemote = onOpenRemote,
    onOpenCharacters = onOpenCharacters,
    onExportSession = onExportSession
)
