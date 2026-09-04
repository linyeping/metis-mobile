package com.mrgreenapps.a11ypilot

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.mrgreenapps.a11ypilot.EventLog
import com.mrgreenapps.a11ypilot.data.AppThemeMode
import kotlinx.coroutines.launch
import com.mrgreenapps.a11ypilot.data.AppThemeStyle
import com.mrgreenapps.a11ypilot.data.ThemeSettings
import com.mrgreenapps.a11ypilot.data.WorkMode
import com.mrgreenapps.a11ypilot.remote.RemoteScreen
import com.mrgreenapps.a11ypilot.remote.RemoteViewModel
import com.mrgreenapps.a11ypilot.ui.AppViewModel
import com.mrgreenapps.a11ypilot.ui.screens.CharacterChatScreen
import com.mrgreenapps.a11ypilot.ui.screens.ChatScreen
import com.mrgreenapps.a11ypilot.ui.screens.FilePreviewScreen
import com.mrgreenapps.a11ypilot.ui.screens.ResourceHubScreen
import com.mrgreenapps.a11ypilot.ui.screens.SettingsScreen
import com.mrgreenapps.a11ypilot.ui.screens.WorkspacePanel
import com.mrgreenapps.a11ypilot.ui.theme.A11yPilotTheme
import java.io.File

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()
    private val remoteViewModel: RemoteViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val launchAction = intent?.action
        setContent {
            val themeStyle by ThemeSettings.getThemeStyle(this).collectAsState(initial = AppThemeStyle.CLAUDE)
            val themeMode by ThemeSettings.getThemeMode(this).collectAsState(initial = AppThemeMode.SYSTEM)
            val fontScale by ThemeSettings.getFontScale(this).collectAsState(initial = 1f)
            A11yPilotTheme(themeStyle = themeStyle, themeMode = themeMode, fontScale = fontScale) {
                MainScreen(
                    viewModel = viewModel,
                    remoteViewModel = remoteViewModel,
                    initialDestination = when (launchAction) {
                        com.mrgreenapps.a11ypilot.widget.MetisWidgetProvider.ACTION_SETTINGS -> MainDestination.SETTINGS
                        else -> MainDestination.WORKSPACE
                    },
                    launchNewChat = launchAction == com.mrgreenapps.a11ypilot.widget.MetisWidgetProvider.ACTION_NEW_CHAT
                )
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
        }
    }

    companion object {
        private const val NOTIFICATION_PERMISSION_REQUEST = 7101
    }
}

internal enum class MainDestination {
    WORKSPACE,
    SETTINGS,
    IMAGES,
    LIBRARY,
    PROJECTS,
    AUTOMATION,
    REMOTE,
    CHARACTERS
}

/**
 * AUTOMATION 目的地下分两个页面：任务列表 vs 单条编辑。
 * Editor.taskId == null 表示新建模式，非空表示编辑该 id 的任务。
 */
private sealed interface AutomationRoute {
    data object List : AutomationRoute
    data class Editor(val taskId: String?) : AutomationRoute
}

@Composable
internal fun MainScreen(
    viewModel: AppViewModel,
    remoteViewModel: RemoteViewModel,
    initialDestination: MainDestination = MainDestination.WORKSPACE,
    launchNewChat: Boolean = false
) {
    val context = LocalContext.current
    val exportScope = rememberCoroutineScope()
    var destination by rememberSaveable { mutableStateOf(initialDestination) }
    var previewFilePath by rememberSaveable { mutableStateOf<String?>(null) }
    val remoteUiState by remoteViewModel.uiState.collectAsState()

    // AUTOMATION 子页面状态：列表/编辑。离开 AUTOMATION 时重置回列表。
    var automationRoute by rememberSaveable(
        stateSaver = androidx.compose.runtime.saveable.Saver(
            save = { route ->
                when (route) {
                    AutomationRoute.List -> ""
                    is AutomationRoute.Editor -> route.taskId ?: "NEW"
                }
            },
            restore = { saved ->
                if (saved.isEmpty()) AutomationRoute.List
                else if (saved == "NEW") AutomationRoute.Editor(null)
                else AutomationRoute.Editor(saved)
            }
        )
    ) { mutableStateOf<AutomationRoute>(AutomationRoute.List) }
    LaunchedEffect(destination) {
        if (destination != MainDestination.AUTOMATION) {
            automationRoute = AutomationRoute.List
        }
    }

    // 桌面小组件「新对话」入口：启动时创建一个新会话
    LaunchedEffect(launchNewChat) {
        if (launchNewChat) {
            destination = MainDestination.WORKSPACE
            viewModel.createSession()
        }
    }

    if (previewFilePath != null) {
        FilePreviewScreen(filePath = previewFilePath.orEmpty(), onBack = { previewFilePath = null })
        return
    }

    if (destination != MainDestination.WORKSPACE) {
        AnimatedVisibility(
            visible = true,
            enter = fadeIn(tween(180)) + slideInHorizontally(tween(220)) { it / 5 },
            exit = fadeOut(tween(120))
        ) {
            AnimatedContent(targetState = destination, label = "main-destination") { target ->
                when (target) {
                    MainDestination.SETTINGS -> SettingsScreen(onExit = { destination = MainDestination.WORKSPACE })
                    MainDestination.IMAGES -> ResourceHubScreen(
                        panel = WorkspacePanel.IMAGES,
                        onBack = { destination = MainDestination.WORKSPACE },
                        onOpenFile = { previewFilePath = it }
                    )
                    MainDestination.LIBRARY -> ResourceHubScreen(
                        panel = WorkspacePanel.LIBRARY,
                        onBack = { destination = MainDestination.WORKSPACE },
                        onOpenFile = { previewFilePath = it }
                    )
                    MainDestination.PROJECTS -> ResourceHubScreen(
                        panel = WorkspacePanel.PROJECTS,
                        onBack = { destination = MainDestination.WORKSPACE },
                        onOpenFile = { previewFilePath = it }
                    )
                    MainDestination.AUTOMATION -> {
                        when (val route = automationRoute) {
                            AutomationRoute.List -> com.mrgreenapps.a11ypilot.ui.screens.AutomationListScreen(
                                context = context,
                                onAddTask = { automationRoute = AutomationRoute.Editor(null) },
                                onEditTask = { id -> automationRoute = AutomationRoute.Editor(id) },
                                onBack = { destination = MainDestination.WORKSPACE },
                                onNotice = { com.mrgreenapps.a11ypilot.EventLog.append("automation> $it") }
                            )
                            is AutomationRoute.Editor -> com.mrgreenapps.a11ypilot.ui.screens.AutomationEditScreen(
                                context = context,
                                taskId = route.taskId,
                                onSaved = { automationRoute = AutomationRoute.List },
                                onBack = { automationRoute = AutomationRoute.List },
                                onNotice = { com.mrgreenapps.a11ypilot.EventLog.append("automation> $it") }
                            )
                        }
                    }
                    MainDestination.REMOTE -> RemoteScreen(
                        uiState = remoteUiState,
                        onConnect = remoteViewModel::connect,
                        onSendCommand = remoteViewModel::sendCommand,
                        onAnswerPermission = remoteViewModel::answerPermission,
                        onCancelRun = remoteViewModel::cancelRun,
                        onDisconnect = remoteViewModel::disconnect,
                        onBack = { destination = MainDestination.WORKSPACE }
                    )
                    MainDestination.CHARACTERS -> CharacterChatScreen(
                        onStartChat = { cardId ->
                            viewModel.createSession(WorkMode.CHAT, cardId)
                            destination = MainDestination.WORKSPACE
                        },
                        onManage = { destination = MainDestination.SETTINGS },
                        onBack = { destination = MainDestination.WORKSPACE }
                    )
                    MainDestination.WORKSPACE -> Unit
                }
            }
        }
        return
    }

    val sessions by viewModel.sessions.collectAsState()
    val activeSessionId by viewModel.activeSessionId.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val agentState by viewModel.agentState.collectAsState()
    val composerAttachments by viewModel.composerAttachments.collectAsState()
    val imageGenerating by viewModel.imageGenerating.collectAsState()
    val imageSettings by viewModel.imageSettings.collectAsState()
    val imageCapabilities by viewModel.imageCapabilities.collectAsState()
    val imageCapabilitiesLoading by viewModel.imageCapabilitiesLoading.collectAsState()
    val pendingApproval by viewModel.pendingApproval.collectAsState()

    pendingApproval?.let { approval ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { viewModel.respondApproval(false) },
            title = { androidx.compose.material3.Text("确认执行操作") },
            text = {
                androidx.compose.material3.Text(
                    "Agent 想执行一个可能影响手机状态的操作：\n\n${approval.summary}\n\n是否允许？"
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { viewModel.respondApproval(true) }) {
                    androidx.compose.material3.Text("允许")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { viewModel.respondApproval(false) }) {
                    androidx.compose.material3.Text("拒绝")
                }
            }
        )
    }

    val copyMessage: (String) -> Unit = { text ->
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("Metis 消息", text))
    }
    val shareMessage: (String) -> Unit = { text ->
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                },
                "分享消息"
            )
        )
    }

    /**
     * 导出当前会话为 Markdown：调 ViewModel 生成 Markdown，写到 cacheDir/exports/
     * 下以 FileProvider 暴露，通过系统分享面板送出。失败时给出最小可读提示。
     */
    val exportSession: () -> Unit = {
        exportScope.launch {
            val markdown = viewModel.exportCurrentSessionAsMarkdown()
            if (markdown.isNullOrBlank()) {
                EventLog.append("export> 当前会话无内容可导出")
                return@launch
            }
            try {
                val exportDir = java.io.File(context.cacheDir, "exports").apply { mkdirs() }
                val safeTitle = (viewModel.activeSessionId.value ?: "session").take(24)
                    .replace(Regex("[^A-Za-z0-9_-]"), "_")
                val file = java.io.File(exportDir, "metis-${safeTitle}-${System.currentTimeMillis()}.md")
                file.writeText(markdown, Charsets.UTF_8)
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                context.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/markdown"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        },
                        "导出会话（Markdown）"
                    )
                )
            } catch (t: Throwable) {
                EventLog.append("export> failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    var pendingCameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(viewModel::importAttachment)
    }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importAttachment)
    }
    val imageReferenceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importImageReference)
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        val uri = pendingCameraUri?.let(Uri::parse)
        if (captured && uri != null) viewModel.importAttachment(uri)
        pendingCameraUri = null
    }
    val takePhoto = {
        val file = File.createTempFile("metis-camera-", ".jpg", context.cacheDir)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        pendingCameraUri = uri.toString()
        cameraLauncher.launch(uri)
    }

    ChatScreen(
        mode = WorkMode.CHAT,
        sessions = sessions,
        activeSessionId = activeSessionId,
        messages = messages,
        agentState = agentState,
        onCreateSession = { viewModel.createSession(WorkMode.CHAT) },
        onSelectSession = viewModel::selectSession,
        onRenameSession = viewModel::renameSession,
        onDeleteSession = viewModel::deleteSession,
        onTogglePinSession = viewModel::togglePinnedSession,
        onSendMessage = viewModel::sendMessage,
        onEditAndResendMessage = viewModel::editAndResendMessage,
        onRegenerateMessage = viewModel::regenerateAssistantMessage,
        onCopyMessage = copyMessage,
        onShareMessage = shareMessage,
        onCancel = viewModel::cancelAgent,
        onOpenFile = { previewFilePath = it },
        onUpdateSessionConfig = viewModel::updateSessionConfig,
        composerAttachments = composerAttachments,
        onPickGallery = { galleryLauncher.launch("image/*") },
        onTakePhoto = takePhoto,
        onPickFile = { fileLauncher.launch(arrayOf("*/*")) },
        onGenerateImage = viewModel::generateImage,
        imageSettings = imageSettings,
        imageCapabilities = imageCapabilities,
        imageCapabilitiesLoading = imageCapabilitiesLoading,
        onSaveImageSettings = viewModel::saveImageSettings,
        onPickImageReference = { imageReferenceLauncher.launch(arrayOf("image/*")) },
        onProbeImageCapabilities = viewModel::probeImageCapabilities,
        onRemoveAttachment = viewModel::removeComposerAttachment,
        isImageGenerating = imageGenerating,
        bottomBarInset = 0.dp,
        onOpenSettings = { destination = MainDestination.SETTINGS },
        onOpenImages = { destination = MainDestination.IMAGES },
        onOpenLibrary = { destination = MainDestination.LIBRARY },
        onOpenProjects = { destination = MainDestination.PROJECTS },
        onOpenAutomation = { destination = MainDestination.AUTOMATION },
        onOpenRemote = { destination = MainDestination.REMOTE },
        onOpenCharacters = { destination = MainDestination.CHARACTERS },
        onExportSession = exportSession
    )
}
