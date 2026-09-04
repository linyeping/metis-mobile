package com.mrgreenapps.a11ypilot.ui

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mrgreenapps.a11ypilot.agent.AgentEngine
import com.mrgreenapps.a11ypilot.agent.AgentExecutionService
import com.mrgreenapps.a11ypilot.agent.AgentSettings
import com.mrgreenapps.a11ypilot.agent.AgentTaskCoordinator
import com.mrgreenapps.a11ypilot.agent.CharacterCard
import com.mrgreenapps.a11ypilot.agent.GroupMentionParser
import com.mrgreenapps.a11ypilot.agent.ImageGenerationClient
import com.mrgreenapps.a11ypilot.data.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val sessionRepository = SessionRepository(application)
    private val imageSettingsRepository = ImageGenerationSettingsRepository(application)
    private val _currentMode = MutableStateFlow(WorkMode.CHAT)
    val currentMode: StateFlow<WorkMode> = _currentMode.asStateFlow()

    // Keep the header/config sheet responsive while DataStore emits the persisted session.
    private val _sessionOverrides = MutableStateFlow<Map<String, Session>>(emptyMap())

    val sessions: StateFlow<List<Session>> = combine(
        sessionRepository.observeSessions(),
        _sessionOverrides
    ) { stored, overrides ->
        stored.map { overrides[it.id] ?: it }
    }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val activeSessionId: StateFlow<String?> = sessionRepository
        .observeUnifiedActiveSessionId()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _composerAttachments = MutableStateFlow<List<String>>(emptyList())
    val composerAttachments: StateFlow<List<String>> = _composerAttachments.asStateFlow()

    private val _imageGenerating = MutableStateFlow(false)
    val imageGenerating: StateFlow<Boolean> = _imageGenerating.asStateFlow()

    private val _imageSettings = MutableStateFlow(ImageGenerationSettings())
    val imageSettings: StateFlow<ImageGenerationSettings> = _imageSettings.asStateFlow()

    private val _imageCapabilities = MutableStateFlow(ImageCapabilities.conservative())
    val imageCapabilities: StateFlow<ImageCapabilities> = _imageCapabilities.asStateFlow()

    private val _imageCapabilitiesLoading = MutableStateFlow(false)
    val imageCapabilitiesLoading: StateFlow<Boolean> = _imageCapabilitiesLoading.asStateFlow()

    val agentState: StateFlow<AgentEngine.State> = AgentTaskCoordinator.state
    val agentUsage: StateFlow<AgentEngine.Usage> = AgentTaskCoordinator.usage
    val pendingApproval: StateFlow<AgentEngine.PendingApproval?> = AgentTaskCoordinator.pendingApproval

    private var activeAssistantMessageId: String? = null
    private var activeRunSessionId: String? = null
    private val createSessionMutex = Mutex()

    init {
        viewModelScope.launch {
            activeSessionId.collectLatest { sessionId ->
                _messages.value = sessionId?.let { sessionRepository.getMessages(it) }.orEmpty()
                _imageSettings.value = sessionId?.let { imageSettingsRepository.get(it) }
                    ?: ImageGenerationSettings()
                _imageCapabilities.value = ImageCapabilities.conservative()
            }
        }
        viewModelScope.launch { ensureSession(WorkMode.CHAT) }
        viewModelScope.launch {
            agentState.collect { state -> syncAgentMessage(state) }
        }
    }

    fun setCurrentMode(mode: WorkMode) {
        if (_currentMode.value == mode) return
        _currentMode.value = mode
        _composerAttachments.value = emptyList()
        viewModelScope.launch { ensureSession(mode) }
    }

    fun createSession(mode: WorkMode? = null, characterCardId: String? = null) {
        val targetMode = mode ?: _currentMode.value
        viewModelScope.launch {
            createSessionMutex.withLock {
                val existing = sessionRepository.observeSessionsByMode(targetMode).first()
                // A character-bound session should never be reused as an empty generic session.
                val reusableEmpty = existing.firstOrNull {
                    sessionRepository.getMessages(it.id).isEmpty() && it.characterCardId == null
                }
                if (reusableEmpty != null && characterCardId == null) {
                    sessionRepository.setActiveSession(reusableEmpty.id)
                    return@withLock
                }

                val settings = AgentSettings.snapshot(getApplication())
                val safety = SafetySettings.getSafetyLevel(getApplication()).first()
                val provider = settings.defaultProvider
                val model = with(AgentSettings) { settings.defaultModelFor(provider) }
                val cardName = characterCardId?.let { id ->
                    AgentSettings.characterCardById(getApplication(), id).first()?.name
                }
                val title = cardName?.let { "与 $it 的对话" }
                    ?: "${targetMode.titleZh()}会话 ${existing.size + 1}"
                val session = sessionRepository.createSession(
                    mode = targetMode,
                    provider = provider,
                    model = model,
                    reasoningIntensity = ReasoningCatalog.defaultFor(provider, model),
                    safetyLevel = safety,
                    title = title,
                    characterCardId = characterCardId
                )
                sessionRepository.setActiveSession(session.id)
            }
        }
    }

    fun selectSession(sessionId: String) {
        viewModelScope.launch {
            sessionRepository.getSession(sessionId)?.let {
                _currentMode.value = WorkMode.CHAT
                if (it.mode != WorkMode.CHAT) sessionRepository.updateSession(it.copy(mode = WorkMode.CHAT))
                sessionRepository.setUnifiedActiveSession(it.id)
            }
        }
    }

    fun sendMessage(content: String, attachments: List<String> = _composerAttachments.value) {
        if (content.isBlank() && attachments.isEmpty()) return
        viewModelScope.launch {
            val mode = _currentMode.value
            val sessionId = ensureSession(mode)
            val normalizedContent = content.trim().ifBlank { "请处理我上传的附件。" }
            val groupMembers = detectGroupMembers(normalizedContent)
            val userMessage = Message(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                role = MessageRole.USER,
                content = normalizedContent,
                timestamp = System.currentTimeMillis(),
                attachments = attachments.ifEmpty { null }
            )
            sessionRepository.addMessage(userMessage)
            val currentSession = sessionRepository.getSession(sessionId)
            if (currentSession != null && SessionTitleGenerator.isDefault(currentSession.title, mode)) {
                sessionRepository.renameSession(sessionId, SessionTitleGenerator.generate(normalizedContent, mode))
            }
            // 群组模式：每位成员会各自产生一条 assistant Message，GroupCoordinator 自己在
            // AgentEngine.runGroupLoop 里写。这里不再预占位，避免多塞一条无 speaker 的空泡。
            if (groupMembers.isNotEmpty()) {
                activeAssistantMessageId = null
                activeRunSessionId = sessionId
                _messages.value = sessionRepository.getMessages(sessionId)
                _composerAttachments.value = emptyList()
                // 同步把群成员清单写入 Session，WorkScreen 顶部 banner 与抽屉徽章立即可用
                val updatedSession = sessionRepository.getSession(sessionId)?.copy(
                    groupMemberIds = groupMembers.map { it.id }.distinct()
                ) ?: return@launch
                sessionRepository.updateSession(updatedSession)
                AgentExecutionService.start(
                    getApplication(),
                    normalizedContent + attachmentSuffix(attachments),
                    updatedSession,
                    sessionId,
                    assistantMessageId = ""
                )
                return@launch
            }
            val assistantMessage = Message(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                role = MessageRole.ASSISTANT,
                content = "",
                timestamp = System.currentTimeMillis(),
                status = MessageStatus.IN_PROGRESS,
                thinkingState = mode.defaultThinkingState()
            )
            sessionRepository.addMessage(assistantMessage)
            activeAssistantMessageId = assistantMessage.id
            activeRunSessionId = sessionId
            _messages.value = sessionRepository.getMessages(sessionId)
            val session = sessionRepository.getSession(sessionId) ?: return@launch
            _composerAttachments.value = emptyList()
            AgentExecutionService.start(
                getApplication(),
                normalizedContent + attachmentSuffix(attachments),
                session,
                sessionId,
                assistantMessage.id
            )
        }
    }

    private fun attachmentSuffix(attachments: List<String>): String {
        if (attachments.isEmpty()) return ""
        return "\n\n附件路径（由 SAF 导入 Metis 工作区）：\n" + attachments.joinToString("\n")
    }

    /**
     * 解析当前指令里的 @提及。仅当命中 ≥ 2 个角色卡时视为群组模式；返回的成员列表会
     * 透传给 AgentEngine.runLoop，让它走 [GroupCoordinator] 路径。空列表表示普通单成员对话。
     */
    private suspend fun detectGroupMembers(instruction: String): List<CharacterCard> {
        val context = getApplication<Application>()
        val cards = AgentSettings.characterCards(context).first()
        val active = AgentSettings.activeCharacter(context).first()
        val parsed = GroupMentionParser.parse(instruction, cards, active)
        return if (parsed.mentioned.size > 1) parsed.mentioned else emptyList()
    }

    /** Replace the latest user prompt, discard its old run, and execute it again. */
    fun editAndResendMessage(messageId: String, content: String) {
        if (content.isBlank()) return
        viewModelScope.launch {
            val oldMessage = _messages.value.firstOrNull { it.id == messageId }
                ?: return@launch
            if (oldMessage.role != MessageRole.USER) return@launch
            val session = sessionRepository.getSession(oldMessage.sessionId) ?: return@launch
            sessionRepository.updateMessage(oldMessage.copy(content = content.trim(), timestamp = System.currentTimeMillis()))
            sessionRepository.deleteMessagesAfter(session.id, messageId)
            startRun(session, content.trim())
        }
    }

    /** Re-run the user prompt immediately preceding an assistant response. */
    fun regenerateAssistantMessage(messageId: String) {
        viewModelScope.launch {
            val assistant = _messages.value.firstOrNull { it.id == messageId }
                ?: return@launch
            if (assistant.role != MessageRole.ASSISTANT) return@launch
            // 群组成员消息走单成员重新生成通道，不走整轮重发。
            if (assistant.speakerId != null) {
                regenerateGroupMemberMessage(messageId)
                return@launch
            }
            val transcript = sessionRepository.getMessages(assistant.sessionId)
            val assistantIndex = transcript.indexOfFirst { it.id == messageId }
            val prompt = transcript.take(assistantIndex).lastOrNull { it.role == MessageRole.USER }
                ?: return@launch
            val session = sessionRepository.getSession(assistant.sessionId) ?: return@launch
            sessionRepository.deleteMessagesFrom(session.id, messageId)
            startRun(session, prompt.content)
        }
    }

    /**
     * 群组模式下重新生成单成员的回答（不影响其它成员已经发出的气泡）。
     *
     * 走 [AgentEngine.regenerateGroupMember] 这条单独的重新生成路径，会复用最新的 user
     * 消息作上下文，保留 groupMemberIds 与已写入的成员气泡。
     */
    fun regenerateGroupMemberMessage(messageId: String) {
        viewModelScope.launch {
            val assistant = _messages.value.firstOrNull { it.id == messageId }
                ?: return@launch
            if (assistant.role != MessageRole.ASSISTANT || assistant.speakerId == null) return@launch
            activeAssistantMessageId = null
            activeRunSessionId = assistant.sessionId
            AgentTaskCoordinator.regenerateGroupMember(
                getApplication(),
                assistant.sessionId,
                messageId
            )
        }
    }

    private suspend fun startRun(session: Session, instruction: String) {
        val groupMembers = detectGroupMembers(instruction)
        // 群组模式：占位 assistant 消息无意义，GroupCoordinator 会自己写每位成员。
        if (groupMembers.isNotEmpty()) {
            activeAssistantMessageId = null
            activeRunSessionId = session.id
            _messages.value = sessionRepository.getMessages(session.id)
            AgentExecutionService.start(
                getApplication(),
                instruction,
                session,
                session.id,
                assistantMessageId = ""
            )
            return
        }
        val assistantMessage = Message(
            id = UUID.randomUUID().toString(),
            sessionId = session.id,
            role = MessageRole.ASSISTANT,
            content = "",
            timestamp = System.currentTimeMillis(),
            status = MessageStatus.IN_PROGRESS,
            thinkingState = session.mode.defaultThinkingState()
        )
        sessionRepository.addMessage(assistantMessage)
        activeAssistantMessageId = assistantMessage.id
        activeRunSessionId = session.id
        _messages.value = sessionRepository.getMessages(session.id)
        AgentExecutionService.start(
            getApplication(),
            instruction,
            session,
            session.id,
            assistantMessage.id
        )
    }

    /** Copy a SAF-selected document into the app-owned attachment directory. */
    fun importAttachment(uri: Uri) {
        viewModelScope.launch {
            val resolver = getApplication<Application>().contentResolver
            val name = displayName(resolver, uri)
                .ifBlank { "附件-${System.currentTimeMillis()}" }
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
            val directory = File(
                getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                    ?: getApplication<Application>().filesDir,
                "Metis/attachments"
            ).apply { mkdirs() }
            val target = nextAvailableFile(File(directory, name))
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            } ?: return@launch
            _composerAttachments.update { current -> (current + target.absolutePath).distinct() }
        }
    }

    fun removeComposerAttachment(path: String) {
        _composerAttachments.update { it.filterNot { item -> item == path } }
    }

    fun probeImageCapabilities() {
        if (_imageCapabilitiesLoading.value) return
        viewModelScope.launch {
            _imageCapabilitiesLoading.value = true
            try {
                val settings = AgentSettings.snapshot(getApplication())
                _imageCapabilities.value = ImageGenerationClient(
                    apiKey = settings.gptApiKey,
                    baseUrl = settings.baseUrl
                ).probeCapabilities()
                normalizeImageSettingsForCapabilities()
            } catch (_: Exception) {
                _imageCapabilities.value = ImageCapabilities.conservative()
                normalizeImageSettingsForCapabilities()
            } finally {
                _imageCapabilitiesLoading.value = false
            }
        }
    }

    fun saveImageSettings(settings: ImageGenerationSettings) {
        val sessionId = activeSessionId.value ?: return
        val normalized = normalizeImageSettings(settings, _imageCapabilities.value)
        // Update the in-memory snapshot before launching persistence so a send immediately
        // after closing the sheet uses the selection the user just made.
        _imageSettings.value = normalized
        viewModelScope.launch {
            imageSettingsRepository.save(sessionId, normalized)
        }
    }

    /** Copy a reference image into app storage, then attach it only to this session's image preset. */
    fun importImageReference(uri: Uri) {
        val sessionId = activeSessionId.value ?: return
        viewModelScope.launch {
            val resolver = getApplication<Application>().contentResolver
            val sourceName = displayName(resolver, uri).ifBlank { "reference-${System.currentTimeMillis()}.png" }
            val extension = sourceName.substringAfterLast('.', "png").lowercase().take(5)
            val directory = File(
                getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                    ?: getApplication<Application>().filesDir,
                "Metis/references"
            ).apply { mkdirs() }
            val target = nextAvailableFile(File(directory, "reference-${System.currentTimeMillis()}.$extension"))
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            } ?: return@launch
            val updated = _imageSettings.value.copy(referenceImagePath = target.absolutePath)
            imageSettingsRepository.save(sessionId, updated)
            if (activeSessionId.value == sessionId) _imageSettings.value = updated
        }
    }

    fun generateImage(prompt: String) {
        if (prompt.isBlank() || _imageGenerating.value) return
        viewModelScope.launch {
            val mode = _currentMode.value
            val sessionId = ensureSession(mode)
            val userMessage = Message(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                role = MessageRole.USER,
                content = "生成图片：${prompt.trim()}",
                timestamp = System.currentTimeMillis()
            )
            val assistantMessage = Message(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                role = MessageRole.ASSISTANT,
                content = "",
                timestamp = System.currentTimeMillis(),
                status = MessageStatus.IN_PROGRESS,
                thinkingState = ThinkingState.WORKING
            )
            sessionRepository.addMessage(userMessage)
            sessionRepository.addMessage(assistantMessage)
            _messages.value = sessionRepository.getMessages(sessionId)
            _imageGenerating.value = true
            try {
                val settings = AgentSettings.snapshot(getApplication())
                val attachedReference = _composerAttachments.value
                    .asSequence()
                    .map(::File)
                    .firstOrNull { it.isFile && it.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp") }
                    ?.absolutePath
                val currentImageSettings = _imageSettings.value.copy(
                    referenceImagePath = attachedReference ?: _imageSettings.value.referenceImagePath
                )
                val images = ImageGenerationClient(
                    apiKey = settings.gptApiKey,
                    baseUrl = settings.baseUrl
                ).generate(
                    prompt = prompt,
                    outputDirectory = File(
                        getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                            ?: getApplication<Application>().filesDir,
                        "Metis/generated"
                    ),
                    settings = currentImageSettings,
                    capabilities = _imageCapabilities.value
                )
                if (images.isEmpty()) throw IllegalStateException("图片 API 返回了空结果")
                sessionRepository.updateMessage(
                    assistantMessage.copy(
                        content = "已生成 ${images.size} 张图片（GPT 图片模型）",
                        status = MessageStatus.COMPLETE,
                        thinkingState = null,
                        attachments = images.map(File::getAbsolutePath)
                    )
                )
            } catch (error: Exception) {
                sessionRepository.updateMessage(
                    assistantMessage.copy(
                        content = "图片生成失败：${error.message ?: "未知错误"}",
                        status = MessageStatus.ERROR,
                        thinkingState = null
                    )
                )
            } finally {
                _imageGenerating.value = false
                if (activeSessionId.value == sessionId) {
                    _messages.value = sessionRepository.getMessages(sessionId)
                }
            }
        }
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
        }.orEmpty()

    private fun nextAvailableFile(original: File): File {
        if (!original.exists()) return original
        val stem = original.nameWithoutExtension
        val extension = original.extension.takeIf { it.isNotBlank() }?.let { ".${it}" }.orEmpty()
        var index = 2
        var candidate: File
        do {
            candidate = File(original.parentFile, "$stem-$index$extension")
            index++
        } while (candidate.exists())
        return candidate
    }

    fun updateSessionConfig(
        sessionId: String,
        provider: ModelProvider,
        model: String,
        reasoningIntensity: ReasoningIntensity,
        safetyLevel: SafetyLevel
    ) {
        viewModelScope.launch {
            sessionRepository.getSession(sessionId)?.let {
                val updated = it.copy(
                    provider = provider,
                    model = model,
                    reasoningIntensity = ReasoningCatalog.normalize(provider, model, reasoningIntensity),
                    safetyLevel = safetyLevel
                )
                _sessionOverrides.update { overrides -> overrides + (sessionId to updated) }
                sessionRepository.updateSession(updated)
            }
        }
    }

    fun renameSession(sessionId: String, title: String) {
        viewModelScope.launch { sessionRepository.renameSession(sessionId, title) }
    }

    fun togglePinnedSession(sessionId: String) {
        viewModelScope.launch {
            sessionRepository.getSession(sessionId)?.let { session ->
                sessionRepository.setPinned(sessionId, !session.isPinned)
            }
        }
    }

    fun regenerateSessionTitle(sessionId: String) {
        viewModelScope.launch {
            val session = sessionRepository.getSession(sessionId) ?: return@launch
            val firstPrompt = sessionRepository.getMessages(sessionId)
                .firstOrNull { it.role == MessageRole.USER }
                ?.content
                .orEmpty()
            sessionRepository.renameSession(
                sessionId,
                SessionTitleGenerator.generate(firstPrompt, session.mode)
            )
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            val deletedMode = sessionRepository.getSession(sessionId)?.mode ?: _currentMode.value
            sessionRepository.deleteSession(sessionId)
            imageSettingsRepository.clear(sessionId)
            _sessionOverrides.update { it - sessionId }
            if (activeSessionId.value == sessionId || _currentMode.value == deletedMode) ensureSession(WorkMode.CHAT)
        }
    }

    private suspend fun normalizeImageSettingsForCapabilities() {
        val sessionId = activeSessionId.value ?: return
        val normalized = normalizeImageSettings(_imageSettings.value, _imageCapabilities.value)
        imageSettingsRepository.save(sessionId, normalized)
        if (activeSessionId.value == sessionId) _imageSettings.value = normalized
    }

    private fun normalizeImageSettings(
        settings: ImageGenerationSettings,
        capabilities: ImageCapabilities
    ): ImageGenerationSettings {
        // Keep per-session choices visible even when the relay does not publish a
        // capabilities endpoint. ImageGenerationClient gates the actual request fields.
        val aspect = settings.aspectRatio
        val resolution = settings.resolution
        val quality = when (settings.quality) {
            ImageQuality.STANDARD -> settings.quality.takeIf {
                capabilities.qualities.any { value -> value.lowercase() in setOf("standard", "medium", "low") }
            }
            ImageQuality.HIGH -> settings.quality.takeIf {
                capabilities.qualities.any { value -> value.equals("high", ignoreCase = true) }
            }
            ImageQuality.AUTO -> ImageQuality.AUTO
        } ?: ImageQuality.AUTO
        val background = settings.background.takeIf {
            when (it) {
                ImageBackground.AUTO -> true
                ImageBackground.TRANSPARENT -> capabilities.backgroundValues.any { value ->
                    value.equals("transparent", ignoreCase = true)
                }
                ImageBackground.SOLID -> capabilities.backgroundValues.any { value ->
                    value.equals("solid", ignoreCase = true) || value.equals("opaque", ignoreCase = true)
                }
            }
        } ?: ImageBackground.AUTO
        return settings.copy(
            aspectRatio = aspect,
            resolution = resolution,
            quality = quality,
            count = settings.count.coerceIn(1, 4),
            background = background,
            // Standard /v1/images/edits accepts the reference as multipart even when the
            // relay does not expose an optional capabilities metadata endpoint.
            referenceImagePath = settings.referenceImagePath
        )
    }

    fun cancelAgent() = AgentTaskCoordinator.cancel(getApplication())

    fun respondApproval(approved: Boolean) = AgentTaskCoordinator.respondApproval(getApplication(), approved)

    /**
     * 把当前会话导出为 Markdown 字符串。群组会话按发言人分组，单条对话则按时间顺序。
     * 用于 [com.mrgreenapps.a11ypilot.utils.GroupExporter]，调用方负责把字符串写到文件并分享。
     */
    suspend fun exportCurrentSessionAsMarkdown(): String? {
        val sessionId = activeSessionId.value ?: return null
        val session = sessionRepository.getSession(sessionId) ?: return null
        val transcript = sessionRepository.getMessages(sessionId)
        return com.mrgreenapps.a11ypilot.utils.GroupExporter.toMarkdown(session, transcript)
    }

    suspend fun exportCurrentSessionAsJson(): String? {
        val sessionId = activeSessionId.value ?: return null
        val session = sessionRepository.getSession(sessionId) ?: return null
        val transcript = sessionRepository.getMessages(sessionId)
        return com.mrgreenapps.a11ypilot.utils.GroupExporter.toJson(session, transcript)
    }

    private suspend fun ensureSession(mode: WorkMode): String {
        val currentId = sessionRepository.observeUnifiedActiveSessionId().first()
        val current = currentId?.let { sessionRepository.getSession(it) }
        if (current != null) {
            if (current.mode != mode) sessionRepository.updateSession(current.copy(mode = mode))
            return current.id
        }

        val existing = sessionRepository.observeSessions().first().firstOrNull()
        if (existing != null) {
            sessionRepository.updateSession(existing.copy(mode = mode))
            sessionRepository.setUnifiedActiveSession(existing.id)
            return existing.id
        }

        val settings = AgentSettings.snapshot(getApplication())
        val safety = SafetySettings.getSafetyLevel(getApplication()).first()
        val provider = settings.defaultProvider
        val model = with(AgentSettings) { settings.defaultModelFor(provider) }
        return sessionRepository.createSession(
            mode = mode,
            provider = provider,
            model = model,
            reasoningIntensity = ReasoningCatalog.defaultFor(provider, model),
            safetyLevel = safety,
            title = "${mode.titleZh()}会话 1"
        ).id
    }

    private suspend fun syncAgentMessage(state: AgentEngine.State) {
        val sessionId = activeRunSessionId ?: return
        // 群组模式（activeAssistantMessageId 为空）下仍然要让 UI 跟上 AgentEngine 写入的
        // 每位成员气泡，所以无差别地按当前 session 重新拉一遍 transcript。
        val activeMessageId = activeAssistantMessageId
        if (state !is AgentEngine.State.Idle && activeMessageId != null) {
            val message = sessionRepository.getMessages(sessionId).firstOrNull { it.id == activeMessageId }
            if (message != null) {
                val updated = when (state) {
                    AgentEngine.State.Idle -> message
                    is AgentEngine.State.Running -> message.copy(
                        status = MessageStatus.IN_PROGRESS,
                        thinkingState = state.thinkingState,
                        toolCalls = if (state.step > 0) {
                            listOf(ToolCall(state.last.substringBefore('('), System.currentTimeMillis()))
                        } else null
                    )
                    is AgentEngine.State.Done -> message.copy(
                        content = state.summary,
                        status = if (state.success) MessageStatus.COMPLETE else MessageStatus.ERROR,
                        thinkingState = null,
                        attachments = state.attachments.ifEmpty { null },
                        toolCalls = state.toolCalls.ifEmpty { message.toolCalls.orEmpty() }
                    )
                    is AgentEngine.State.Error -> message.copy(
                        content = if (state.message == "cancelled") "任务已停止" else "出错了：${state.message}",
                        status = MessageStatus.ERROR,
                        thinkingState = null,
                        toolCalls = message.toolCalls
                    )
                }
                sessionRepository.updateMessage(updated)
            }
        }
        if (activeSessionId.value == sessionId) {
            _messages.value = sessionRepository.getMessages(sessionId)
        }
        if (state is AgentEngine.State.Done || state is AgentEngine.State.Error) {
            activeAssistantMessageId = null
            activeRunSessionId = null
        }
    }
}

private fun WorkMode.titleZh(): String = when (this) {
    WorkMode.CHAT -> "聊天"
    WorkMode.COWORK -> "协作"
    WorkMode.CODE -> "编程"
}

private fun WorkMode.defaultThinkingState(): ThinkingState = when (this) {
    WorkMode.CHAT -> ThinkingState.UNDERSTANDING
    WorkMode.COWORK -> ThinkingState.WORKING
    WorkMode.CODE -> ThinkingState.EXECUTING
}
