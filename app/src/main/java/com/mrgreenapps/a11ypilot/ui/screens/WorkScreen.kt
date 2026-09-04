package com.mrgreenapps.a11ypilot.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.animation.core.tween
import com.mrgreenapps.a11ypilot.agent.AgentEngine
import com.mrgreenapps.a11ypilot.agent.AgentSettings
import com.mrgreenapps.a11ypilot.agent.CharacterCard
import com.mrgreenapps.a11ypilot.agent.GroupMentionParser
import com.mrgreenapps.a11ypilot.agent.SpeechInput
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.mrgreenapps.a11ypilot.data.*
import com.mrgreenapps.a11ypilot.ui.components.MessageBubble
import com.mrgreenapps.a11ypilot.ui.components.MessageComposer
import com.mrgreenapps.a11ypilot.ui.components.SessionDrawer
import com.mrgreenapps.a11ypilot.ui.components.SessionConfigSection
import com.mrgreenapps.a11ypilot.ui.components.SessionConfigSheet
import com.mrgreenapps.a11ypilot.ui.components.ImageSettingsSheet
import com.mrgreenapps.a11ypilot.utils.ResponsiveLayout
import kotlinx.coroutines.launch
import coil.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkScreen(
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
    onExportSession: () -> Unit = {}
) {
    val context = LocalContext.current
    val characterCards by AgentSettings.characterCards(context).collectAsState(initial = emptyList())
    // 把角色卡铺平到 map，用于按 speakerId 迅速取回头像 / 名字，
    // 避免 LazyColumn items 内对每条消息都做一次 list 线性查找。
    val characterCardsById: Map<String, CharacterCard> = remember(characterCards) {
        characterCards.associateBy { it.id }
    }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val imeBottom = with(density) { WindowInsets.ime.getBottom(this).toDp() }
    val navigationBottom = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    // The keyboard and three-button navigation bar are mutually exclusive on most devices,
    // but OEMs can report both during the transition. Reserve only the larger inset so the
    // composer never sits under the navigation bar or jumps by the combined height.
    val systemBottomInset = maxOf(imeBottom, navigationBottom)
    val imeVisible = imeBottom > 0.dp
    var inputText by remember(mode) { mutableStateOf("") }
    var showSessionConfig by remember { mutableStateOf(false) }
    var sessionConfigSection by remember { mutableStateOf(SessionConfigSection.MODEL) }
    var showAttachmentSheet by remember { mutableStateOf(false) }
    var showImageSettings by remember { mutableStateOf(false) }
    var imagePromptMode by remember(mode) { mutableStateOf(false) }
    var editingMessageId by remember(activeSessionId) { mutableStateOf<String?>(null) }
    var composerHeightPx by remember { mutableIntStateOf(0) }
    // @ 自动补全：根据光标前最近的 @ 触发符计算候选。
    data class MentionCandidate(val member: CharacterCard, val replaceRange: IntRange, val prefix: String)
    var mentionCandidate by remember(inputText) { mutableStateOf<MentionCandidate?>(null) }
    if (mentionCandidate == null) {
        // 在 inputText 变化时尝试探测最近一次 @ 触发。
        val caret = inputText.length
        val atIdx = inputText.lastIndexOf('@', caret - 1)
        val matchPrefix = if (atIdx >= 0 && (atIdx == 0 || inputText[atIdx - 1].isWhitespace() ||
                inputText.substring(atIdx).matches(Regex("@[^\\s@，。,.!?？、:：]*"))
                )) {
            val after = inputText.substring(atIdx + 1)
            if (after.matches(Regex("[^\\s@，。,.!?？、:：]*"))) after else null
        } else null
        if (atIdx >= 0 && matchPrefix != null) {
            val candidates = characterCards
                .filter { it.name.contains(matchPrefix, ignoreCase = true) || matchPrefix.isEmpty() }
                .take(5)
            if (candidates.isNotEmpty()) {
                mentionCandidate = MentionCandidate(
                    member = candidates.first(),
                    replaceRange = atIdx..(atIdx + 1 + matchPrefix.length),
                    prefix = matchPrefix
                )
            }
        }
    }
    // Voice input
    val speechInput = remember { SpeechInput(context) }
    var listening by remember { mutableStateOf(false) }
    var voiceError by remember { mutableStateOf<String?>(null) }
    var startVoiceListening by remember { mutableStateOf<(() -> Unit)?>(null) }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startVoiceListening?.invoke() else { listening = false; voiceError = "需要录音权限才能使用语音输入" }
    }
    startVoiceListening = {
        listening = true
        voiceError = null
        speechInput.start(object : SpeechInput.Listener {
            override fun onPartial(text: String) { inputText = text }
            override fun onFinal(text: String) { inputText = text; listening = false }
            override fun onError(message: String) { listening = false; voiceError = message }
            override fun onEnd() { listening = false }
        })
    }
    fun startVoiceInput() {
        if (listening) {
            speechInput.stop()
            return
        }
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            return
        }
        startVoiceListening?.invoke()
    }
    val isRunning = agentState is AgentEngine.State.Running
    val horizontalPadding = ResponsiveLayout.responsiveHorizontalPadding()
    // Keep transcript content close to the screen edge on phones; the composer keeps its own padding.
    val messageHorizontalPadding = if (ResponsiveLayout.isCompactLayout()) 8.dp else horizontalPadding
    val spacing = ResponsiveLayout.responsiveSpacing()
    val activeSession = sessions.firstOrNull { it.id == activeSessionId }

    if (showSessionConfig && activeSession != null) {
        SessionConfigSheet(
            session = activeSession,
            onDismiss = { showSessionConfig = false },
            section = sessionConfigSection,
            onSave = { provider, model, reasoning, safety ->
                onUpdateSessionConfig(activeSession.id, provider, model, reasoning, safety)
            }
        )
    }

    if (showImageSettings && imagePromptMode) {
        ImageSettingsSheet(
            initial = imageSettings,
            capabilities = imageCapabilities,
            probing = imageCapabilitiesLoading,
            onDismiss = { showImageSettings = false },
            onSave = onSaveImageSettings,
            onPickReference = onPickImageReference
        )
    }

    if (showAttachmentSheet) {
        ModalBottomSheet(onDismissRequest = { showAttachmentSheet = false }) {
            AttachmentMenuItem(
                icon = Icons.Default.PhotoLibrary,
                label = "相册",
                onClick = { showAttachmentSheet = false; onPickGallery() }
            )
            AttachmentMenuItem(
                icon = Icons.Default.CameraAlt,
                label = "相机",
                onClick = { showAttachmentSheet = false; onTakePhoto() }
            )
            AttachmentMenuItem(
                icon = Icons.Default.AttachFile,
                label = "文件",
                onClick = { showAttachmentSheet = false; onPickFile() }
            )
            AttachmentMenuItem(
                icon = Icons.Default.Image,
                label = "图片生成",
                onClick = {
                    showAttachmentSheet = false
                    imagePromptMode = true
                    inputText = ""
                    onProbeImageCapabilities()
                }
            )
            Spacer(Modifier.height(12.dp))
        }
    }

    val lastMessage = messages.lastOrNull()
    val agentScrollKey = when (val state = agentState) {
        is AgentEngine.State.Running -> "running:${state.step}:${state.last}"
        is AgentEngine.State.Done -> "done:${state.steps}"
        is AgentEngine.State.Error -> "error:${state.steps}:${state.message}"
        AgentEngine.State.Idle -> "idle"
    }
    suspend fun scrollTranscriptToLatest() {
        if (messages.isNotEmpty()) {
            // The last bubble can be taller than the viewport. A large positive offset lets
            // LazyColumn clamp to its bottom edge instead of leaving its lower half under the
            // composer or IME.
            listState.scrollToItem(messages.lastIndex, Int.MAX_VALUE / 4)
        }
    }

    LaunchedEffect(
        activeSessionId,
        messages.size,
        lastMessage?.id,
        lastMessage?.content,
        lastMessage?.status,
        agentScrollKey
    ) {
        if (messages.isNotEmpty()) {
            withFrameNanos { }
            scrollTranscriptToLatest()
        }
    }

    LaunchedEffect(imeVisible, imeBottom, composerHeightPx) {
        if (imeVisible && messages.isNotEmpty()) {
            // Wait for the IME animation/layout pass before calculating the final scroll range.
            withFrameNanos { }
            scrollTranscriptToLatest()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            SessionDrawer(
                sessions = sessions,
                activeSessionId = activeSessionId,
                characterCards = characterCards,
                onCreateSession = onCreateSession,
                onSelectSession = {
                    onSelectSession(it)
                    scope.launch { drawerState.close() }
                },
                onRenameSession = onRenameSession,
                onDeleteSession = onDeleteSession,
                onTogglePinSession = onTogglePinSession,
                onOpenSettings = onOpenSettings,
                onOpenImages = onOpenImages,
                onOpenLibrary = onOpenLibrary,
                onOpenProjects = onOpenProjects,
                onOpenAutomation = onOpenAutomation,
                onOpenRemote = onOpenRemote,
                onOpenCharacters = onOpenCharacters
            )
        }
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = activeSession?.title ?: "Metis",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            // 群组会话：在标题下方渲染参与成员头像 + 人数徽章。
                            if (activeSession != null && activeSession.groupMemberIds.isNotEmpty()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(top = 2.dp)
                                ) {
                                    val members = activeSession.groupMemberIds
                                        .mapNotNull { id -> characterCardsById[id] }
                                    members.take(4).forEach { card ->
                                        com.mrgreenapps.a11ypilot.ui.components.CharacterAvatar(
                                            card = card,
                                            size = 18.dp,
                                            corner = 5.dp
                                        )
                                        Spacer(Modifier.width(3.dp))
                                    }
                                    if (members.size > 4) {
                                        Text(
                                            text = "+${members.size - 4}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "群组对话 · ${members.size} 人",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, "会话列表")
                        }
                    },
                    actions = {
                        IconButton(onClick = onExportSession) {
                            Icon(Icons.Default.Share, "导出会话")
                        }
                    }
                )
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = bottomBarInset)
                ) {
                    if (messages.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            EmptyWorkspace(
                                mode = mode,
                                onCreateSession = onCreateSession,
                                onPromptExample = { inputText = it }
                            )
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                // Keep the transcript viewport full width. Individual bubbles own
                                // their max width and explicit horizontal alignment.
                                .fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = messageHorizontalPadding,
                                end = messageHorizontalPadding,
                                top = spacing,
                                bottom = with(density) {
                                    composerHeightPx.toDp().coerceAtLeast(50.dp) +
                                        bottomBarInset + systemBottomInset + 8.dp
                                }
                            ),
                            verticalArrangement = Arrangement.spacedBy(spacing)
                        ) {
                            itemsIndexed(items = messages, key = { _, msg -> msg.id }) { index, message ->
                                val isLiveMessage = message.status == MessageStatus.IN_PROGRESS &&
                                    message == messages.lastOrNull()
                                val speakerId = message.speakerId
                                val isFirstInSpeakerGroup = if (speakerId == null) true
                                else index == 0 || messages[index - 1].speakerId != speakerId
                                val isLastInSpeakerGroup = if (speakerId == null) true
                                else index == messages.lastIndex || messages[index + 1].speakerId != speakerId
                                val speakerCard = speakerId?.let { characterCardsById[it] }
                                MessageBubble(
                                    message = message,
                                    modifier = Modifier.fillMaxWidth(),
                                    liveAgentState = if (isLiveMessage) agentState else null,
                                    onOpenFile = onOpenFile,
                                    isLatestUserMessage = message.id == messages.lastOrNull { it.role == MessageRole.USER }?.id,
                                    onEdit = { edited ->
                                        editingMessageId = edited.id
                                        inputText = edited.content
                                    },
                                    onCopy = { copied -> onCopyMessage(copied.content) },
                                    onShare = { shared -> onShareMessage(shared.content) },
                                    onRegenerate = { regenerated -> onRegenerateMessage(regenerated.id) },
                                    speakerName = message.speakerName ?: speakerCard?.name,
                                    speakerAvatarUri = speakerCard?.avatarUri,
                                    isFirstInSpeakerGroup = isFirstInSpeakerGroup,
                                    isLastInSpeakerGroup = isLastInSpeakerGroup
                                )
                            }
                        }
                    }
                }

                // @ 自动补全 popup：当 inputText 里出现 @ 时弹出候选列表。点击候选后插入「@名字 」并清掉 popup。
                if (mentionCandidate != null) {
                    val candidates = characterCards
                        .filter { it.name.contains(mentionCandidate!!.prefix, ignoreCase = true) || mentionCandidate!!.prefix.isEmpty() }
                        .take(5)
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = horizontalPadding)
                            .padding(bottom = bottomBarInset)
                            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                            // Popup 出现在 composer 之上 ~ composer 高度 + 8.dp
                            .padding(bottom = with(density) { composerHeightPx.toDp().coerceAtLeast(70.dp) + 8.dp })
                            .shadow(
                                elevation = 8.dp,
                                shape = RoundedCornerShape(14.dp),
                                ambientColor = Color.Black.copy(alpha = 0.06f),
                                spotColor = Color.Black.copy(alpha = 0.12f)
                            )
                            .background(
                                color = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(14.dp)
                            )
                    ) {
                        Column(modifier = Modifier.padding(6.dp)) {
                            Text(
                                text = "选择要 @ 的角色",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 10.dp, top = 4.dp, bottom = 4.dp)
                            )
                            candidates.forEach { card ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            val range = mentionCandidate!!.replaceRange
                                            inputText = inputText.replaceRange(range, "@${card.name} ")
                                            mentionCandidate = null
                                        }
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    com.mrgreenapps.a11ypilot.ui.components.CharacterAvatar(
                                        card = card,
                                        size = 26.dp,
                                        corner = 6.dp
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(card.name, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            text = if (card.allowPhoneUse) "可操作手机" else "仅对话",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    // Keep the composer visually floating without the opaque dark edge produced by
                    // the platform Card shadow on some Android GPU drivers.
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = horizontalPadding)
                        .padding(bottom = bottomBarInset)
                        // Apply one inset-aware bottom offset. The parent is edge-to-edge and
                        // the composer must not add navigation and IME insets a second time.
                        .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                        .padding(bottom = 6.dp)
                        .shadow(
                            elevation = 10.dp,
                            shape = RoundedCornerShape(22.dp),
                            clip = false,
                            ambientColor = Color.Black.copy(alpha = 0.07f),
                            spotColor = Color.Black.copy(alpha = 0.13f)
                        )
                        .animateContentSize(animationSpec = tween(220))
                ) {
                    MessageComposer(
                        inputText = inputText,
                        onInputTextChange = { inputText = it },
                        placeholderText = mode.placeholderZh(),
                        imagePromptMode = imagePromptMode,
                        onExitImagePromptMode = {
                            imagePromptMode = false
                            inputText = ""
                        },
                        attachments = composerAttachments,
                        onRemoveAttachment = onRemoveAttachment,
                        onOpenAttachment = onOpenFile,
                        voiceError = voiceError,
                        isListening = listening,
                        isRunning = isRunning,
                        isImageGenerating = isImageGenerating,
                        editingMessageId = editingMessageId,
                        onCancelEdit = {
                            editingMessageId = null
                            inputText = ""
                        },
                        modelLabel = activeSession?.model,
                        onShowModelConfig = if (activeSession != null) {
                            {
                                sessionConfigSection = SessionConfigSection.MODEL
                                showSessionConfig = true
                            }
                        } else null,
                        safetyLabel = activeSession?.safetyLevel?.shortZh(),
                        onShowSafetyConfig = if (activeSession != null) {
                            {
                                sessionConfigSection = SessionConfigSection.SAFETY
                                showSessionConfig = true
                            }
                        } else null,
                        imageSettingsLabel = if (imagePromptMode)
                            imageSettings.shortLabel(imageCapabilities) else null,
                        onShowImageSettings = if (imagePromptMode) {
                            { showImageSettings = true }
                        } else null,
                        showAttachmentSheet = showAttachmentSheet,
                        onShowAttachmentSheet = { showAttachmentSheet = it },
                        onPickGallery = onPickGallery,
                        onTakePhoto = onTakePhoto,
                        onPickFile = onPickFile,
                        onSendMessage = onSendMessage,
                        onEditAndResendMessage = onEditAndResendMessage,
                        onGenerateImage = onGenerateImage,
                        onCancel = onCancel,
                        startVoiceInput = ::startVoiceInput,
                        onHeightChange = { composerHeightPx = it }
                    )
                }
            }
        }
    }
}

@Composable
private fun AttachmentMenuItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(label) },
        tonalElevation = 0.dp
    )
}

@Composable
private fun EmptyWorkspace(
    mode: WorkMode,
    onCreateSession: () -> Unit,
    onPromptExample: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 420.dp)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        AnimatedVisibility(
            visible = true,
            enter = fadeIn(tween(300)) + slideInVertically(tween(360)) { it / 5 }
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = mode.emptyTitleZh(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = mode.descriptionZh(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(18.dp))
                mode.samplePromptsZh().firstOrNull()?.let { prompt ->
                    SuggestionPrompt(
                        text = prompt,
                        delayMillis = 80,
                        onClick = { onPromptExample(prompt.removePrefix("试试说：")) }
                    )
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onCreateSession) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("开始新会话")
                }
            }
        }
    }
}

@Composable
private fun SuggestionPrompt(text: String, delayMillis: Int, onClick: () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(delayMillis.toLong())
        visible = true
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(260)),
        exit = fadeOut(tween(120))
    ) {
        Surface(
            modifier = Modifier
                .wrapContentWidth()
                .clickable(onClick = onClick),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ) {
            Text(
                text = text,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

internal fun WorkMode.titleZh(): String = when (this) {
    WorkMode.CHAT -> "聊天"
    WorkMode.COWORK -> "协作"
    WorkMode.CODE -> "编程"
}

private fun WorkMode.descriptionZh(): String = when (this) {
    WorkMode.CHAT -> "日常聊天、问答和简单代码"
    WorkMode.COWORK -> "写文件、完成作业并操作手机应用"
    WorkMode.CODE -> "编写代码、运行命令并整理开发文件"
}

private fun WorkMode.placeholderZh(): String = when (this) {
    WorkMode.CHAT -> "发消息给 Metis"
    WorkMode.COWORK -> "描述要完成的文件或手机任务"
    WorkMode.CODE -> "描述要编写或运行的代码"
}

private fun WorkMode.emptyTitleZh(): String = when (this) {
    WorkMode.CHAT -> "开始聊天"
    WorkMode.COWORK -> "交给 Metis 处理"
    WorkMode.CODE -> "开始编程任务"
}

private fun WorkMode.samplePromptsZh(): List<String> = when (this) {
    WorkMode.CHAT -> listOf("试试说：帮我总结今天最重要的三件事")
    WorkMode.COWORK -> listOf("试试说：写一份今日总结")
    WorkMode.CODE -> listOf("试试说：写一个读取CSV的Python脚本")
}

private fun SafetyLevel.shortZh(): String = when (this) {
    SafetyLevel.STRICT -> "严格"
    SafetyLevel.BALANCED -> "平衡"
    SafetyLevel.PERMISSIVE -> "宽松"
    SafetyLevel.RESEARCH -> "研究"
}
