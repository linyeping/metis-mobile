package com.mrgreenapps.a11ypilot.agent

import android.content.Context
import com.mrgreenapps.a11ypilot.EventLog
import com.mrgreenapps.a11ypilot.data.ThinkingState
import com.mrgreenapps.a11ypilot.data.WorkMode
import com.mrgreenapps.a11ypilot.data.ModelProvider
import com.mrgreenapps.a11ypilot.data.Message
import com.mrgreenapps.a11ypilot.data.MessageRole
import com.mrgreenapps.a11ypilot.data.MessageStatus
import com.mrgreenapps.a11ypilot.data.Session
import com.mrgreenapps.a11ypilot.data.SessionRepository
import com.mrgreenapps.a11ypilot.data.ToolCall
import com.mrgreenapps.a11ypilot.data.UsageEntry
import com.mrgreenapps.a11ypilot.data.UsageRepository
import com.mrgreenapps.a11ypilot.tools.DocumentTool
import com.mrgreenapps.a11ypilot.tools.TermuxCommandRunner
import com.mrgreenapps.a11ypilot.tools.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.UUID

/**
 * Drives the conversational tool-use loop:
 *   instruction → snapshot → Claude → tool_use → ToolDispatcher → tool_result → Claude → … → done
 *
 * 职责：
 *  1. 维护会话状态机（Idle / Running / Done / Error）。
 *  2. 装配系统提示词 + 历史摘要 + 当前屏幕，调用 API。
 *  3. 重试（RetryPolicy）和用户确认（PendingApproval）。
 *  4. 工具分发委托给 [ToolDispatcher]，安全策略由 [SafetyEvaluator] 决定。
 *  5. 会话结束写入摘要给下次启动使用。
 */
class AgentEngine(
    appContext: Context
) {
    private val ctx = appContext.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val executor = ToolExecutor(
        ctx,
        excludeOwnPackage = false
    )
    private val documentTool = DocumentTool(ctx)
    private val termuxCommandRunner = TermuxCommandRunner(ctx)
    private val dispatcher = ToolDispatcher(
        appContext = ctx,
        executor = executor,
        documentTool = documentTool,
        termuxCommandRunner = termuxCommandRunner,
    )
    // 群组模式直接由本类向仓库写入每位成员的 assistant Message（带 speakerId），
    // 而非依赖 AgentExecutionService 的单一占位 assistant 消息。单成员模式不写。
    private val sessionRepository = SessionRepository(ctx)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _usage = MutableStateFlow(Usage())
    val usage: StateFlow<Usage> = _usage.asStateFlow()

    private val _turns = MutableStateFlow<List<Turn>>(emptyList())
    val turns: StateFlow<List<Turn>> = _turns.asStateFlow()

    private var runJob: Job? = null

    // Approval gate for irreversible actions. When SafetyEvaluator returns Confirm, the run loop
    // suspends and emits a pending request; the UI surfaces it and the user approves or rejects.
    private val _pendingApproval = MutableStateFlow<PendingApproval?>(null)
    val pendingApproval: StateFlow<PendingApproval?> = _pendingApproval.asStateFlow()
    private var approvalResult: kotlinx.coroutines.CompletableDeferred<Boolean>? = null

    data class PendingApproval(
        val tool: String,
        val summary: String
    )

    /** Approve or reject the currently pending action. */
    fun respondApproval(approved: Boolean) {
        val d = approvalResult
        if (d != null && !d.isCompleted) d.complete(approved)
        _pendingApproval.value = null
    }

    /**
     * Suspend the run loop and wait for the user to approve or reject an irreversible action.
     * Emits [PendingApproval] for the UI and blocks on a CompletableDeferred resolved by
     * [respondApproval]. Returns false if the run is cancelled while waiting.
     */
    private suspend fun awaitApproval(tool: String, summary: String): Boolean {
        val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
        approvalResult = deferred
        _pendingApproval.value = PendingApproval(tool, summary)
        EventLog.append("agent> awaiting approval for $tool: $summary")
        return try {
            deferred.await()
        } finally {
            approvalResult = null
            _pendingApproval.value = null
        }
    }

    sealed class State {
        data object Idle : State()
        data class Running(
            val step: Int,
            val last: String,
            val thinkingState: ThinkingState,
            val toolCalls: List<ToolCall> = emptyList(),
            val maxSteps: Int = 0
        ) : State()
        data class Done(
            val success: Boolean,
            val summary: String,
            val steps: Int,
            val attachments: List<String> = emptyList(),
            val toolCalls: List<ToolCall> = emptyList()
        ) : State()
        data class Error(val message: String, val steps: Int, val toolCalls: List<ToolCall> = emptyList()) : State()
    }

    /** Aggregate token totals across all API turns of the current run. */
    data class Usage(
        val turns: Int = 0,
        val input: Int = 0,
        val cacheRead: Int = 0,
        val cacheCreation: Int = 0,
        val output: Int = 0
    ) {
        val billedInput: Int get() = input + cacheCreation
        val totalTokens: Int get() = input + cacheRead + cacheCreation + output
    }

    /** Per-API-turn details so the UI can show what each step cost. */
    data class Turn(
        val turn: Int,
        val tools: List<String>,
        val input: Int,
        val cacheRead: Int,
        val cacheCreation: Int,
        val output: Int
    )

    fun cancel() {
        runJob?.cancel()
        runJob = null
        if (_state.value is State.Running) {
            _state.value = State.Error("cancelled", (_state.value as State.Running).step)
        }
    }

    /**
     * 重新生成某一条群组成员消息：仅对该成员重新跑一次 LLM 回合，保留其余成员已发内容。
     *
     * 工作流：
     *  1. 根据 messageId 找到原成员回复（必须存在 speakerId，否则不是群组消息）。
     *  2. 把这条 Message 重置为 IN_PROGRESS + 清空 content + 重置 thinkingState。
     *  3. 启动新 runJob 调用 [regenerateGroupMemberLoop]，只针对该成员。
     *
     * @return true 表示已开始重新生成；false 表示该消息不是群组成员消息。
     */
    fun regenerateGroupMember(sessionId: String, messageId: String): Boolean {
        cancel()
        runJob = scope.launch {
            val session = sessionRepository.getSession(sessionId) ?: return@launch
            val target = sessionRepository.getMessages(sessionId)
                .firstOrNull { it.id == messageId } ?: return@launch
            val memberId = target.speakerId ?: return@launch
            val member = AgentSettings.characterCardById(ctx, memberId).first()
                ?: run {
                    EventLog.append("agent> regenerate group member: card $memberId not found")
                    _state.value = State.Error("找不到原成员角色卡（$memberId）", 0)
                    return@launch
                }
            sessionRepository.updateMessage(
                target.copy(
                    content = "",
                    status = MessageStatus.IN_PROGRESS,
                    thinkingState = ToolRegistry.thinkingStateFor(session.mode, null)
                )
            )
            val userName = AgentSettings.profileNickname(ctx).first()
            regenerateGroupMemberLoop(session, member, userName)
        }
        return runJob != null
    }

    private suspend fun regenerateGroupMemberLoop(
        session: Session,
        member: CharacterCard,
        userName: String
    ) {
        // 重生成单成员的 prompt：复用上一条 user 消息作为上下文（指令源）。
        val messages = sessionRepository.getMessages(session.id)
        val latestUser = messages.lastOrNull { it.role == MessageRole.USER }
        val instruction = latestUser?.content ?: "请重新回应"
        _state.value = State.Running(
            step = 1,
            last = "${member.name} 重新生成中",
            thinkingState = ToolRegistry.thinkingStateFor(session.mode, null),
            toolCalls = emptyList(),
            maxSteps = AgentSettings.snapshot(ctx).maxSteps
        )
        try {
            GroupCoordinator(ctx).coordinate(instruction, session, listOf(member), userName).collect { event ->
                when (event) {
                    is GroupCoordinator.MemberEvent.Thinking -> {
                        // 单成员重新生成也保持 thinking 状态可见，但 state 文本简化为「正在重生成」
                        _state.value = State.Running(
                            step = 1,
                            last = "${member.name} 重新生成中",
                            thinkingState = ToolRegistry.thinkingStateFor(session.mode, null),
                            toolCalls = emptyList(),
                            maxSteps = AgentSettings.snapshot(ctx).maxSteps
                        )
                    }
                    is GroupCoordinator.MemberEvent.Replied -> {
                        // 重生成只跑一个成员，Replied 事件就是它。找到这条 message 的当前
                        // 拷贝（最早一次 IN_PROGRESS 被覆盖），用新内容替换。
                        val reply = event.reply
                        val refreshed = sessionRepository.getMessages(session.id)
                            .firstOrNull { it.speakerId == member.id && it.status == MessageStatus.IN_PROGRESS }
                            ?: return@collect
                        val newContent = buildString {
                            if (reply.content.isNotBlank()) append(reply.content)
                            if (!reply.succeeded) {
                                if (isNotEmpty()) append("\n\n")
                                append("⚠️ ").append(reply.error ?: "重生成失败")
                            }
                        }.ifBlank { "（空回复）" }
                        sessionRepository.updateMessage(
                            refreshed.copy(
                                content = newContent,
                                status = if (reply.succeeded) MessageStatus.COMPLETE else MessageStatus.ERROR,
                                thinkingState = null,
                                toolCalls = reply.toolSummary.takeIf { it.isNotEmpty() }
                                    ?.map { ToolCall(it, System.currentTimeMillis(), "done") }
                            )
                        )
                    }
                    is GroupCoordinator.MemberEvent.Completed -> {
                        val total = event.replies.size
                        val ok = event.replies.count { it.succeeded }
                        _state.value = State.Done(
                            success = ok > 0,
                            summary = if (total == 0) "重新生成失败" else "${member.name} 已重新生成",
                            steps = 1,
                            attachments = emptyList(),
                            toolCalls = emptyList()
                        )
                    }
                    else -> Unit
                }
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            _state.value = State.Error("cancelled", 1)
            throw ce
        } catch (t: Throwable) {
            EventLog.append("agent> regenerate group member failed: ${t.javaClass.simpleName}: ${t.message}")
            _state.value = State.Error(ApiErrorMessage.fromThrowable(t), 1)
        }
    }

    fun run(instruction: String, session: Session) {
        cancel()
        dispatcher.resetGeneratedFiles()
        _usage.value = Usage()
        _turns.value = emptyList()
        runJob = scope.launch { runLoop(instruction, session) }
    }

    private suspend fun runLoop(instruction: String, session: Session) {
        val mode = session.mode
        val settings = AgentSettings.snapshot(ctx)
        val retryEnabled = AgentSettings.retryEnabled(ctx).first()
        val retryAttempts = AgentSettings.retryAttempts(ctx).first()
        val activeCharacter = AgentSettings.characterCardById(ctx, session.characterCardId).first()
            ?: AgentSettings.activeCharacter(ctx).first()
        val phoneEnabled = activeCharacter?.allowPhoneUse ?: true
        val userName = AgentSettings.profileNickname(ctx).first()

        // 群组智能体：解析指令中的 @提及，命中多个角色时进入群聊模式。
        val allCards = AgentSettings.characterCards(ctx).first()
        val groupMention = GroupMentionParser.parse(instruction, allCards, activeCharacter)
        val groupMembers = if (groupMention.mentioned.size > 1) groupMention.mentioned else emptyList()
        if (groupMembers.isNotEmpty()) {
            EventLog.append("agent> group mode: ${groupMembers.joinToString { it.name }}")
            // 群聊模式下每位被 @ 的成员都各自产生一条 assistant Message，单一占位
            // assistant 消息没意义，所以我们直接接管仓库写入（不依赖外部 service）。
            runGroupLoop(instruction, session, groupMembers, userName)
            return
        }

        EventLog.append("agent> START: $instruction")
        _state.value = State.Running(0, "准备任务", ToolRegistry.thinkingStateFor(mode, null), maxSteps = settings.maxSteps)

        // Resolve a key for every API turn. The router keeps healthy keys in rotation and
        // transparently fails over on authentication, rate-limit, upstream, and network errors.
        val complete: suspend (List<AnthropicClient.Message>) -> AnthropicClient.Reply = { history ->
            ApiKeyRouter.complete(ctx, session.provider, session.model) { apiKey, baseUrl, profileId ->
                EventLog.append("agent> using api profile=$profileId")
                when (session.provider) {
                    // CUSTOM_CLAUDE is a legacy serialized value; route it through the GPT relay
                    // until the session is rewritten by SessionRepository normalization.
                    ModelProvider.CUSTOM_CLAUDE, ModelProvider.CUSTOM_OPENAI -> OpenAIResponsesClient(
                        apiKey = apiKey,
                        model = session.model,
                        mode = mode,
                        reasoningIntensity = session.reasoningIntensity,
                        personaInstruction = settings.personaInstruction.ifBlank { personaPresetInstruction(settings.personaPreset) },
                        characterCard = activeCharacter,
                        phoneEnabled = phoneEnabled,
                        userName = userName,
                        groupMembers = groupMembers,
                        baseUrl = baseUrl
                    ).complete(history)
                    ModelProvider.DEEPSEEK -> OpenAICompatibleClient(
                        apiKey = apiKey,
                        model = session.model,
                        mode = mode,
                        reasoningIntensity = session.reasoningIntensity,
                        baseUrl = baseUrl,
                        supportsReasoningEffort = false,
                        personaInstruction = settings.personaInstruction.ifBlank { personaPresetInstruction(settings.personaPreset) },
                        characterCard = activeCharacter,
                        phoneEnabled = phoneEnabled,
                        userName = userName,
                        groupMembers = groupMembers
                    ).complete(history)
                }
            }
        }
        val maxSteps = settings.maxSteps
        val history = mutableListOf<AnthropicClient.Message>()

        // Restore the durable session transcript before sending the new instruction. The old
        // loop started every run with an empty list, so a follow-up such as "你写吧" had no
        // reference to the preceding request or the file it produced. The latest user message
        // is the request currently being executed and is added below with the live screen state.
        val persistedMessages = SessionRepository(ctx).getMessages(session.id)
        val currentUserIndex = persistedMessages.indexOfLast { it.role == MessageRole.USER }
        val previousMessages = if (currentUserIndex >= 0) {
            persistedMessages.take(currentUserIndex)
        } else {
            persistedMessages
        }
        history += restoreTranscript(previousMessages)
        if (history.isNotEmpty()) {
            EventLog.append("agent> restored session memory turns=${history.size}")
        }

        val initialUserText = buildString {
            append("INSTRUCTION: ").append(instruction).append("\n\n")
            val memory = dispatcher.readPersistentMemory()
            // 记忆为空/读取失败时跳过注入；读取失败的具体原因已经在 EventLog 里给开发者。
            if (memory.startsWith("（记忆为空）") || memory.startsWith("（记忆读取失败")) {
                // skip injecting memory into the prompt
            } else {
                append("PERSISTENT MEMORY (user preferences carried across sessions):\n")
                append(memory).append("\n\n")
            }
            // 注入会话级摘要：上次干到哪。这样新一轮的「你写吧」「继续」就能拿到关键事实，
            // 不至于完全丢失上下文。摘要可能为空（新会话或已被清空），为空就直接跳过。
            session.summary?.takeIf { it.isNotBlank() }?.let { previousSummary ->
                append("SESSION SUMMARY (上次任务的简短记录，仅供参考）：\n")
                append(previousSummary).append("\n\n")
            }
            when (val initial = executor.dumpScreen()) {
                is ToolExecutor.Result.Ok -> append("CURRENT PHYSICAL SCREEN:\n").append(initial.screen)
                is ToolExecutor.Result.Err -> append("SCREEN WARNING: ").append(initial.message)
                is ToolExecutor.Result.Done -> Unit
            }
        }
        // Vision autopilot: when the accessibility tree is empty (canvas UIs, games, video),
        // attach a screenshot so the model can still see the screen instead of stalling.
        val emptyTree = initialUserText.contains("(no active window)") ||
            initialUserText.substringAfter("CURRENT PHYSICAL SCREEN:").trim().isBlank()
        val initialContent = if (emptyTree) {
            val shot = executor.screenshot()
            if (shot is ToolExecutor.Result.Ok && shot.imageBase64 != null) {
                EventLog.append("agent> empty tree, attached screenshot for vision fallback")
                buildList {
                    add(kotlinx.serialization.json.buildJsonObject {
                        put("type", "text"); put("text", initialUserText)
                    })
                    add(kotlinx.serialization.json.buildJsonObject {
                        put("type", "image")
                        putJsonObject("source") {
                            put("type", "base64")
                            put("media_type", shot.imageMimeType ?: "image/jpeg")
                            put("data", shot.imageBase64)
                        }
                    })
                }
            } else {
                AnthropicClient.userText(initialUserText)
            }
        } else {
            AnthropicClient.userText(initialUserText)
        }
        history.add(AnthropicClient.Message.User(initialContent))

        var step = 0
        val toolHistory = mutableListOf<ToolCall>()
        while (true) {
            if (!scope.isActive || runJob?.isCancelled == true) {
                _state.value = State.Error("cancelled", step)
                persistSessionSummary(session, instruction, step, toolHistory, "用户取消", success = false)
                return
            }
            if (step >= maxSteps) {
                _state.value = State.Error("hit max steps ($maxSteps)", step)
                EventLog.append("agent> aborted: max steps")
                persistSessionSummary(session, instruction, step, toolHistory, "达到最大步骤数 $maxSteps", success = false)
                return
            }

            var reply: AnthropicClient.Reply? = null
            var retryCount = 0
            while (reply == null) {
                try {
                    reply = complete(history)
                } catch (c: kotlinx.coroutines.CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    if (!retryEnabled || !RetryPolicy.shouldRetry(t) || retryCount >= retryAttempts) {
                        EventLog.append("agent> API error: ${t.message}")
                        _state.value = State.Error(ApiErrorMessage.fromThrowable(t), step)
                        return
                    }
                    retryCount++
                    EventLog.append("agent> retry $retryCount/$retryAttempts after ${t.javaClass.simpleName}")
                    _state.value = State.Running(
                        step = step,
                        last = "正在重试 $retryCount/$retryAttempts",
                        thinkingState = ToolRegistry.thinkingStateFor(mode, null),
                        toolCalls = toolHistory.toList(),
                        maxSteps = maxSteps
                    )
                    delay(RetryPolicy.RETRY_INTERVAL_MILLIS)
                }
            }
            val resolvedReply = reply ?: return
            EventLog.append("agent> turn ${_usage.value.turns + 1}  in=${resolvedReply.inputTokens} cache_read=${resolvedReply.cachedInputTokens} cache_create=${resolvedReply.cacheCreationInputTokens} out=${resolvedReply.outputTokens}")
            recordTurn(resolvedReply, session)

            // Persist assistant turn so the next API call can echo it back with tool_results.
            history.add(AnthropicClient.Message.Assistant(resolvedReply.assistantContent))

            if (resolvedReply.toolUses.isEmpty()) {
                val text = resolvedReply.assistantContent
                    .filter { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
                    .mapNotNull { it["text"]?.jsonPrimitive?.contentOrNull }
                    .joinToString("\n")
                    .trim()
                if (text.isBlank()) {
                    val status = resolvedReply.stopReason.ifBlank { "unknown" }
                    _state.value = State.Error(
                        "模型响应结束但没有返回可见文本或工具调用（status=$status）。请检查模型是否支持当前 API 协议与推理参数。",
                        step
                    )
                    return
                }
                EventLog.append("agent> end_turn (no tool): $text")
                _state.value = State.Done(
                    success = true,
                    summary = text,
                    steps = step,
                    attachments = dispatcher.generatedFilesSnapshot,
                    toolCalls = toolHistory.map { it.copy(status = "done") }
                )
                persistSessionSummary(session, instruction, step, toolHistory, text, success = true)
                return
            }

            // We expect ONE tool call per turn per the system prompt; handle all defensively.
            val results = mutableListOf<AnthropicClient.ToolResult>()
            var doneSeen: ToolExecutor.Result.Done? = null
            for (use in resolvedReply.toolUses) {
                step++
                _state.value = State.Running(
                    step,
                    "${use.name}(…)",
                    ToolRegistry.thinkingStateFor(mode, use.name),
                    toolHistory.toList(),
                    maxSteps
                )
                toolHistory += ToolCall(use.name, System.currentTimeMillis(), "running")
                _state.value = State.Running(
                    step,
                    "${use.name}(…)",
                    ToolRegistry.thinkingStateFor(mode, use.name),
                    toolHistory.toList(),
                    maxSteps
                )
                val result = when (val decision = SafetyEvaluator.evaluate(
                    com.mrgreenapps.a11ypilot.data.SafetySettings.getConfigForLevel(session.safetyLevel),
                    use.name,
                    use.input
                )) {
                    SafetyEvaluator.Decision.Allow -> dispatcher.dispatch(use.name, use.input)
                    is SafetyEvaluator.Decision.Block -> {
                        EventLog.append("agent> safety blocked ${use.name}: ${decision.reason}")
                        ToolExecutor.Result.Err(decision.reason)
                    }
                    is SafetyEvaluator.Decision.Confirm -> {
                        // Pause and ask the user before dispatching an irreversible action.
                        val approved = awaitApproval(decision.tool, decision.summary)
                        if (approved) {
                            dispatcher.dispatch(use.name, use.input)
                        } else {
                            EventLog.append("agent> user rejected ${use.name}")
                            ToolExecutor.Result.Err("用户拒绝了操作：${decision.summary}")
                        }
                    }
                }
                when (result) {
                    is ToolExecutor.Result.Ok -> {
                        val content = buildString {
                            append("foreground: ").append(result.foregroundApp).append('\n')
                            append(result.screen)
                        }
                        results.add(AnthropicClient.ToolResult(
                            toolUseId = use.id,
                            text = content,
                            imageBase64 = result.imageBase64,
                            imageMimeType = result.imageMimeType
                        ))
                    }
                    is ToolExecutor.Result.Err -> {
                        results.add(AnthropicClient.ToolResult(use.id, result.message, isError = true))
                    }
                    is ToolExecutor.Result.Done -> {
                        doneSeen = result
                        results.add(AnthropicClient.ToolResult(use.id, "ok"))
                    }
                }
            }
            history.add(AnthropicClient.Message.User(AnthropicClient.userToolResults(results)))

            if (doneSeen != null) {
                _state.value = State.Done(
                    doneSeen.success,
                    doneSeen.summary,
                    step,
                    dispatcher.generatedFilesSnapshot,
                    toolHistory.map { it.copy(status = "done") }
                )
                persistSessionSummary(session, instruction, step, toolHistory, doneSeen.summary, doneSeen.success)
                return
            }
        }
    }

    /**
     * 把这一轮的关键事实压缩成 1200 字以内的会话摘要，写回 [Session.summary]，下次启动时
     * 会优先注入到 prompt 里。这样「写个文档发给微信」这种跨多轮的任务，模型能看到上一轮
     * 已经写好的文件名、已经发到的聊天对象，不会完全丢失上下文。
     */
    private fun persistSessionSummary(
        session: Session,
        instruction: String,
        steps: Int,
        toolHistory: List<ToolCall>,
        finalSummary: String,
        success: Boolean
    ) {
        scope.launch(Dispatchers.IO) {
            val toolLine = if (toolHistory.isEmpty()) "无工具调用"
            else toolHistory.joinToString("、") { it.name.substringBefore('(') }
            val files = dispatcher.generatedFilesSnapshot
            val filesLine = if (files.isEmpty()) "无新增附件"
            else files.joinToString("、") { java.io.File(it).name }
            val snippet = buildString {
                append("指令：").append(instruction.take(160)).append('\n')
                append("结果：").append(if (success) "成功" else "失败").append("；")
                append(finalSummary.take(400)).append('\n')
                append("步骤：").append(steps).append(" 步；工具：").append(toolLine.take(400)).append('\n')
                append("附件：").append(filesLine)
            }
            runCatching {
                SessionRepository(ctx).updateSessionSummary(session.id, snippet, steps)
            }.onFailure { EventLog.append("agent> persist summary failed: ${it.message}") }
        }
    }

    private fun recordTurn(reply: AnthropicClient.Reply, session: Session) {
        val tools = reply.toolUses.map { it.name }
        val u = _usage.value
        val turnIdx = u.turns + 1
        _usage.value = u.copy(
            turns = turnIdx,
            input = u.input + reply.inputTokens,
            cacheRead = u.cacheRead + reply.cachedInputTokens,
            cacheCreation = u.cacheCreation + reply.cacheCreationInputTokens,
            output = u.output + reply.outputTokens
        )
        _turns.value = (_turns.value + Turn(
            turn = turnIdx,
            tools = tools,
            input = reply.inputTokens,
            cacheRead = reply.cachedInputTokens,
            cacheCreation = reply.cacheCreationInputTokens,
            output = reply.outputTokens
        )).takeLast(50)
        scope.launch(Dispatchers.IO) {
            UsageRepository.record(
                ctx,
                UsageEntry(
                    timestamp = System.currentTimeMillis(),
                    sessionId = session.id,
                    provider = session.provider,
                    model = session.model,
                    inputTokens = reply.inputTokens,
                    cachedInputTokens = reply.cachedInputTokens,
                    outputTokens = reply.outputTokens
                )
            )
        }
    }

    private fun personaPresetInstruction(preset: String): String = when (preset) {
        AgentSettings.PERSONA_PRESET_PRACTICAL -> "语气简洁、专注、直接；先给结论，再给必要步骤；不要冗余寒暄。"
        else -> "语气温暖、耐心、贴心，主动确认用户目标；保持协作感，不说空话。"
    }

    /** Convert the persisted transcript into API turns while keeping context bounded. */
    private fun restoreTranscript(messages: List<Message>): List<AnthropicClient.Message> {
        val usable = messages.mapNotNull { message ->
            val content = buildString {
                append(message.content.trim())
                message.attachments.orEmpty().takeIf { it.isNotEmpty() }?.let { paths ->
                    append("\n附件：").append(paths.joinToString("、") { java.io.File(it).name })
                }
                message.toolCalls.orEmpty().takeIf { it.isNotEmpty() }?.let { calls ->
                    append("\n已调用工具：").append(calls.joinToString("、") { it.name })
                }
            }.trim()
            if (content.isBlank()) return@mapNotNull null
            when (message.role) {
                MessageRole.USER -> AnthropicClient.Message.User(AnthropicClient.userText(content))
                MessageRole.ASSISTANT -> AnthropicClient.Message.Assistant(AnthropicClient.userText(content))
                MessageRole.SYSTEM -> null
            }
        }
        val totalChars = usable.sumOf { turn ->
            when (turn) {
                is AnthropicClient.Message.User -> turn.content.toString().length
                is AnthropicClient.Message.Assistant -> turn.content.toString().length
            }
        }
        if (totalChars <= MAX_RESTORED_HISTORY_CHARS || usable.size <= 16) return usable
        // Keep the opening task definition and the latest turns. This mirrors the compaction
        // behavior of desktop coding agents without inventing facts or dropping the active task.
        // Keep duplicate turns: repeating the same prompt is still meaningful context.
        return (usable.take(4) + usable.takeLast(12))
            .also { EventLog.append("agent> compacted session memory ${usable.size}->${it.size} turns") }
    }

    companion object {
        private const val MAX_RESTORED_HISTORY_CHARS = 32_000
    }

    /**
     * 群组多智能体分支：把控制权交给 [GroupCoordinator]，每当某成员被 LLM 调用时就向仓库
     * 写一条独立的 assistant Message，并把自己的 [Message.speakerId]/[Message.speakerName]
     * 填上。每位成员的回复通过 [GroupCoordinator.MemberEvent.Replied] 拿到，立即
     * 把那条 Message 的 content / status 更新。整个流程结束时同步把 _state 推到
     * [State.Done]，与单成员运行保持状态语义一致。
     *
     * 故意不依赖 [AgentExecutionService] 的「单一占位 assistant 消息」机制，因为群聊
     * 一位成员一条消息，按群成员数量会动态增减。
     */
    private suspend fun runGroupLoop(
        instruction: String,
        session: Session,
        members: List<CharacterCard>,
        userName: String
    ) {
        val mode = session.mode
        val settings = AgentSettings.snapshot(ctx)
        EventLog.append("agent> GROUP run members=${members.joinToString { it.name }}")
        // 群组会话：把群成员清单写入 Session，让抽屉/顶部 banner 在回放时也能识别。
        // 已存在则不再覆盖（保留首次发起时的成员，避免后续扩缩群影响历史展示）。
        if (session.groupMemberIds.isEmpty() && members.isNotEmpty()) {
            sessionRepository.updateSession(
                session.copy(
                    groupMemberIds = members.map { it.id }.distinct()
                )
            )
        }
        _state.value = State.Running(
            step = 0,
            last = "准备群组任务",
            thinkingState = ToolRegistry.thinkingStateFor(mode, null),
            toolCalls = emptyList(),
            maxSteps = settings.maxSteps
        )

        // LIFO 栈：每次 Started 推入新生成的 Message.id，Replied 时弹出来更新。
        // 用 ArrayDeque 而不是 Stack，访问更轻量；只在本协程内读写，无需同步。
        val activeIds = ArrayDeque<String>()
        var stepCount = 0

        try {
            GroupCoordinator(ctx).coordinate(instruction, session, members, userName).collect { event ->
                currentCoroutineContext().ensureActive()
                when (event) {
                    is GroupCoordinator.MemberEvent.Started -> {
                        val msg = Message(
                            id = UUID.randomUUID().toString(),
                            sessionId = session.id,
                            role = MessageRole.ASSISTANT,
                            content = "",
                            timestamp = System.currentTimeMillis(),
                            status = MessageStatus.IN_PROGRESS,
                            thinkingState = ToolRegistry.thinkingStateFor(mode, null),
                            speakerId = event.member.id,
                            speakerName = event.member.name
                        )
                        sessionRepository.addMessage(msg)
                        activeIds.addLast(msg.id)
                        stepCount++
                        _state.value = State.Running(
                            step = stepCount,
                            last = "${event.member.name} 正在回答",
                            thinkingState = ToolRegistry.thinkingStateFor(mode, null),
                            toolCalls = emptyList(),
                            maxSteps = settings.maxSteps
                        )
                    }
                    is GroupCoordinator.MemberEvent.Thinking -> {
                        // 成员进入 LLM/工具阶段：刷新气泡的 thinking 状态，让 UI 渲染脉动点。
                        // 这里不产生新 step，只刷新最后一气泡的思考状态。
                        val messageId = activeIds.lastOrNull()
                        if (messageId != null) {
                            sessionRepository.updateMessage(
                                sessionRepository.getMessages(session.id)
                                    .firstOrNull { it.id == messageId }
                                    ?.copy(thinkingState = ToolRegistry.thinkingStateFor(mode, null))
                                    ?: return@collect
                            )
                        }
                        _state.value = State.Running(
                            step = stepCount,
                            last = "${event.member.name} 正在思考",
                            thinkingState = ToolRegistry.thinkingStateFor(mode, null),
                            toolCalls = emptyList(),
                            maxSteps = settings.maxSteps
                        )
                    }
                    is GroupCoordinator.MemberEvent.Replied -> {
                        val reply = event.reply
                        val messageId = activeIds.removeLastOrNull() ?: return@collect
                        val current = sessionRepository.getMessages(session.id)
                            .firstOrNull { it.id == messageId } ?: return@collect
                        val newContent = buildString {
                            if (reply.content.isNotBlank()) append(reply.content)
                            if (!reply.succeeded) {
                                if (isNotEmpty()) append("\n\n")
                                append("⚠️ ").append(reply.error ?: "发言失败")
                            }
                        }.ifBlank { "（空回复）" }
                        sessionRepository.updateMessage(
                            current.copy(
                                content = newContent,
                                status = if (reply.succeeded) MessageStatus.COMPLETE else MessageStatus.ERROR,
                                thinkingState = null,
                                toolCalls = reply.toolSummary.takeIf { it.isNotEmpty() }
                                    ?.map { ToolCall(it, System.currentTimeMillis(), "done") }
                            )
                        )
                    }
                    is GroupCoordinator.MemberEvent.Skipped -> {
                        // 当前实现里 coordinate() 不会 emit Skipped；保留分支以保持事件契约完整。
                        Unit
                    }
                    GroupCoordinator.MemberEvent.Cancelled -> {
                        markGroupInProgressCancelled(session.id, activeIds)
                        _state.value = State.Error("cancelled", stepCount)
                        return@collect
                    }
                    is GroupCoordinator.MemberEvent.Completed -> {
                        markGroupInProgressCancelled(session.id, activeIds)
                        val total = event.replies.size
                        val ok = event.replies.count { it.succeeded }
                        val summary = if (total == 0) {
                            "群组没有成员回复"
                        } else {
                            buildString {
                                append(event.replies.joinToString { it.memberName })
                                if (ok < total) append("（$ok/$total 成功）") else append("已全部回复")
                            }
                        }
                        _state.value = State.Done(
                            success = ok > 0,
                            summary = summary,
                            steps = stepCount,
                            attachments = emptyList(),
                            toolCalls = emptyList()
                        )
                        persistSessionSummary(
                            session,
                            instruction,
                            stepCount,
                            toolHistory = emptyList(),
                            finalSummary = summary,
                            success = ok > 0
                        )
                    }
                }
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            markGroupInProgressCancelled(session.id, activeIds)
            _state.value = State.Error("cancelled", stepCount)
            throw ce
        } catch (t: Throwable) {
            EventLog.append("agent> GROUP failed: ${t.javaClass.simpleName}: ${t.message}")
            markGroupInProgressCancelled(session.id, activeIds)
            _state.value = State.Error(ApiErrorMessage.fromThrowable(t), stepCount)
        }
    }

    /**
     * 群组分支取消/异常收尾：把仍然停留在 IN_PROGRESS 的成员气泡改写成"已停止"，
     * 避免 UI 留下永远转圈的 thinking… 占位条。
     */
    private suspend fun markGroupInProgressCancelled(
        sessionId: String,
        ids: ArrayDeque<String>
    ) {
        if (ids.isEmpty()) return
        val snapshot = sessionRepository.getMessages(sessionId).associateBy { it.id }
        ids.forEach { id ->
            val msg = snapshot[id] ?: return@forEach
            if (msg.status == MessageStatus.IN_PROGRESS) {
                sessionRepository.updateMessage(
                    msg.copy(
                        content = msg.content.ifBlank { "群组回答已停止" },
                        status = MessageStatus.ERROR,
                        thinkingState = null
                    )
                )
            }
        }
    }

}
