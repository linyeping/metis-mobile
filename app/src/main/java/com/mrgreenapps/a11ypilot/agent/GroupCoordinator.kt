package com.mrgreenapps.a11ypilot.agent

import android.content.Context
import com.mrgreenapps.a11ypilot.EventLog
import com.mrgreenapps.a11ypilot.data.ModelProvider
import com.mrgreenapps.a11ypilot.data.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 真正的多智能体群组协调器：每个被 @ 的角色各自进行一次独立的 LLM 调用，
 * 而不是把所有人设塞进同一次提示词让模型"轮流演"。
 *
 * 与最初版本的差异：
 *  - 最初版本：[Prompts.systemForGroup] 把 N 个角色的人设塞进同一份系统提示词，由模型自己负责
 *    按 "名字：内容" 分段输出。一次 API 调用失败 = 全部成员沉默。
 *  - 当前版本：[GroupCoordinator] 对每个角色发起一次独立的 API 调用，注入
 *    [Prompts.systemForMember]，只描述"你正以 X 身份回应"。成员之间串成一轮群聊，
 *    后发言的成员能看到前面所有成员的回复再接话。
 *
 * 当前会话集成：
 *  - AgentEngine 解析 @提及后（命中 ≥ 2 个成员）把控制权交给本类；
 *  - 每个成员回复后被写回会话的 Message 流（按 metadata.speakerId 标记发言人）；
 *  - 单个成员失败不中断整轮，其他成员仍可继续。
 *
 * v2 增强（本次）：
 *  - 每个成员支持「工具循环」：单次 LLM 返回 tool_use 后 dispatch→再调 LLM，
 *    直到返回纯文本或达到 [memberMaxSteps]。意味着 "@肖月 帮我发微信给张三"
 *    真正能调动手机工具执行。
 *  - 事件流新增 [MemberEvent.Thinking]：在调 LLM 之前 emit，让 UI 显示
 *    "X 正在思考…" 脉动点；调 LLM 期间不再触发 Started。
 *  - 串剥前置前缀（"Alice：xxx" → "xxx"）保留。
 */
class GroupCoordinator(
    appContext: Context
) {
    private val ctx = appContext.applicationContext

    /**
     * 一位成员完成的回复。
     *
     * @param memberId 角色卡 id，便于回写到 Message metadata。
     * @param memberName 角色显示名（@提及的搜索键）。
     * @param content 模型返回的纯文本回复（去除 "名字：" 前缀）。
     * @param toolSummary 模型调用过的工具名（仅记录，不持久化全部结果）。
     * @param error 非空时表示该成员失败但整轮继续。
     * @param durationMs 该成员独立 LLM 调用+工具循环耗时（含失败）。
     */
    data class MemberReply(
        val memberId: String,
        val memberName: String,
        val content: String,
        val toolSummary: List<String> = emptyList(),
        val error: String? = null,
        val durationMs: Long = 0
    ) {
        val succeeded: Boolean get() = error == null
    }

    /**
     * 协调器对外暴露的事件流，UI 可以按事件把每位成员的回复渲染成独立气泡。
     */
    sealed class MemberEvent {
        data class Started(val member: CharacterCard) : MemberEvent()
        /**
         * 成员进入「调 LLM / 执行工具」阶段，UI 可以用这个名字渲染脉动指示。
         */
        data class Thinking(val member: CharacterCard) : MemberEvent()
        data class Replied(val reply: MemberReply) : MemberEvent()
        data class Skipped(val member: CharacterCard, val reason: String) : MemberEvent()
        data object Cancelled : MemberEvent()
        data class Completed(val replies: List<MemberReply>) : MemberEvent()
    }

    /**
     * 协调一群角色的回复。
     *
     * @param instruction 用户原始指令（@提及尚未剥离，调用前应已剥离或完整传入）。
     * @param session 当前会话，用于取 provider / model。
     * @param members 这一轮要发言的成员（已去重保序），会被串行依次调用。
     * @param userName 已配置的用户昵称，用于占位符替换。
     * @return 事件流；调用方用 `collect` 收集事件，最后一个 [MemberEvent.Completed] 收尾。
     */
    fun coordinate(
        instruction: String,
        session: Session,
        members: List<CharacterCard>,
        userName: String
    ): Flow<MemberEvent> = flow {
        if (members.isEmpty()) {
            EventLog.append("group> no members to coordinate")
            emit(MemberEvent.Completed(emptyList()))
            return@flow
        }
        EventLog.append("group> START members=${members.joinToString { it.name }}")

        val replies = mutableListOf<MemberReply>()
        // Seed the shared history once with the user instruction. Subsequent turns add
        // member replies to it so later members see what earlier ones said before answering.
        val sharedHistory = mutableListOf<AnthropicClient.Message>().apply {
            add(
                AnthropicClient.Message.User(
                    listOf(
                        buildJsonObject {
                            put("type", "text")
                            put("text", buildString {
                                append("INSTRUCTION:\n")
                                append(instruction.trim())
                                append("\n\n（请按系统提示中的格式，以你所在角色的身份回应。）")
                            })
                        }
                    )
                )
            )
        }

        for ((index, member) in members.withIndex()) {
            try {
                currentCoroutineContext().ensureActive()
            } catch (ce: CancellationException) {
                EventLog.append("group> CANCELLED before member=${member.name}")
                emit(MemberEvent.Cancelled)
                emit(MemberEvent.Completed(replies.toList()))
                return@flow
            }

            emit(MemberEvent.Started(member))
            val startedMs = System.currentTimeMillis()
            val reply = runMemberTurn(
                member = member,
                session = session,
                userName = userName,
                otherMembers = members.filter { it.id != member.id },
                sharedHistory = sharedHistory,
                onThinking = { emit(MemberEvent.Thinking(member)) }
            )
            val withTiming = reply.copy(durationMs = System.currentTimeMillis() - startedMs)
            replies += withTiming
            emit(MemberEvent.Replied(withTiming))

            // Only successful replies propagate forward. Failed ones are surfaced via the
            // event stream but shouldn't poison the conversation context for later members.
            if (withTiming.succeeded && withTiming.content.isNotBlank()) {
                sharedHistory += AnthropicClient.Message.Assistant(
                    listOf(
                        buildJsonObject {
                            put("type", "text")
                            put("text", "${member.name}：${withTiming.content}")
                        }
                    )
                )
            }
            EventLog.append(
                "group> member[${index}] ${member.name} " +
                    (if (withTiming.succeeded) "ok" else "err") +
                    " ms=${withTiming.durationMs} len=${withTiming.content.length} tools=${withTiming.toolSummary.size}" +
                    (withTiming.error?.let { " err=$it" } ?: "")
            )
        }

        EventLog.append("group> COMPLETED replies=${replies.size}")
        emit(MemberEvent.Completed(replies.toList()))
    }

    /**
     * 成员的「一轮」回复：进入 LLM→tool→LLM 循环直到模型给出纯文本或达到 [MEMBER_MAX_STEPS]。
     *
     * 设计：复用 [ToolDispatcher] 让群组成员也能操作手机/读写文件，与单 AgentEngine 共用
     * 同一套工具权限/安全策略。MemberTurn 之间的差异仅在于：
     *  1. system prompt 由 [Prompts.systemForMember] 生成（人设 + 单成员指令）
     *  2. history 起手只包含 user INSTRUCTION + 前序成员回复
     *  3. 不调用 SessionRepository，所有持久化由 AgentEngine.runGroupLoop 处理
     *
     * 整个循环 emit [MemberEvent.Thinking] 让 UI 看到「正在思考…」状态；这一步发生在
     * 首次调 LLM 之前，之后每次 API 调起前也会再 emit 一次。
     */
    private suspend fun runMemberTurn(
        member: CharacterCard,
        session: Session,
        userName: String,
        otherMembers: List<CharacterCard>,
        sharedHistory: List<AnthropicClient.Message>,
        onThinking: suspend () -> Unit
    ): MemberReply {
        val mode = session.mode
        val phoneEnabled = member.allowPhoneUse
        val systemPrompt = Prompts.systemForMember(
            mode = mode,
            member = member,
            userName = userName,
            otherMembers = otherMembers
        )

        // 每个成员独立的 history 副本（不影响其他成员的 sharedHistory）。
        val turnHistory = sharedHistory.map { it }.toMutableList()
        val collectedTools = mutableListOf<String>()
        val dispatcher = ToolDispatcher.createForGroup(ctx)

        try {
            var step = 0
            while (step < MEMBER_MAX_STEPS) {
                currentCoroutineContext().ensureActive()
                onThinking()
                val reply = callLlm(
                    session = session,
                    systemPrompt = systemPrompt,
                    userName = userName,
                    phoneEnabled = phoneEnabled,
                    history = turnHistory
                )
                turnHistory += AnthropicClient.Message.Assistant(reply.assistantContent)
                collectedTools += reply.toolUses.map { it.name }.distinct()

                if (reply.toolUses.isEmpty()) {
                    val content = extractText(reply.assistantContent)
                        .let { stripMemberPrefix(it, member.name) }
                    return MemberReply(
                        memberId = member.id,
                        memberName = member.name,
                        content = content,
                        toolSummary = collectedTools.distinct()
                    )
                }

                step++
                val results = reply.toolUses.map { use ->
                    val result = dispatcher.dispatch(use.name, use.input)
                    AnthropicClient.ToolResult(
                        toolUseId = use.id,
                        text = when (result) {
                            is ToolExecutor.Result.Ok -> "foreground: ${result.foregroundApp}\n${result.screen}"
                            is ToolExecutor.Result.Err -> result.message
                            is ToolExecutor.Result.Done -> "ok"
                        },
                        imageBase64 = (result as? ToolExecutor.Result.Ok)?.imageBase64,
                        imageMimeType = (result as? ToolExecutor.Result.Ok)?.imageMimeType,
                        isError = result is ToolExecutor.Result.Err
                    )
                }
                turnHistory += AnthropicClient.Message.User(AnthropicClient.userToolResults(results))
                EventLog.append("group> member=${member.name} step=$step tools=${reply.toolUses.map { it.name }}")
            }
            // 达到 max steps：尝试给当前 history 中最后一段 Assistant 提取可见文本，
            // 没有就返回当前累积的快照说明工具循环上限。
            val lastText = turnHistory.lastOrNull { it is AnthropicClient.Message.Assistant }
                ?.let { (it as AnthropicClient.Message.Assistant).content.toString() }
                .orEmpty()
            val cleaned = stripMemberPrefix(lastText, member.name)
            return MemberReply(
                memberId = member.id,
                memberName = member.name,
                content = cleaned.ifBlank { "（该角色的工具循环已达上限 $MEMBER_MAX_STEPS 步，未给出最终文本）" },
                toolSummary = collectedTools.distinct(),
                error = null
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            EventLog.append("group> member=${member.name} failed: ${t.javaClass.simpleName}: ${t.message}")
            return MemberReply(
                memberId = member.id,
                memberName = member.name,
                content = "",
                toolSummary = collectedTools.distinct(),
                error = "${t.javaClass.simpleName}: ${t.message ?: "unknown error"}"
            )
        }
    }

    /**
     * 调一次 LLM（独立 try/catch 不在这里：runMemberTurn 的外层 catch 负责把异常
     * 转成 failed MemberReply，便于上层保持一个 Replied 事件链路）。
     */
    private suspend fun callLlm(
        session: Session,
        systemPrompt: String,
        userName: String,
        phoneEnabled: Boolean,
        history: List<AnthropicClient.Message>
    ): AnthropicClient.Reply {
        return ApiKeyRouter.complete(ctx, session.provider, session.model) { apiKey, baseUrl, profileId ->
            EventLog.append("group> member turn profile=$profileId")
            when (session.provider) {
                ModelProvider.CUSTOM_CLAUDE,
                ModelProvider.CUSTOM_OPENAI -> OpenAIResponsesClient(
                    apiKey = apiKey,
                    model = session.model,
                    mode = session.mode,
                    reasoningIntensity = session.reasoningIntensity,
                    customSystemPrompt = systemPrompt,
                    phoneEnabled = phoneEnabled,
                    userName = userName,
                    baseUrl = baseUrl
                ).complete(history)

                ModelProvider.DEEPSEEK -> OpenAICompatibleClient(
                    apiKey = apiKey,
                    model = session.model,
                    mode = session.mode,
                    reasoningIntensity = session.reasoningIntensity,
                    baseUrl = baseUrl,
                    supportsReasoningEffort = false,
                    customSystemPrompt = systemPrompt,
                    phoneEnabled = phoneEnabled,
                    userName = userName
                ).complete(history)
            }
        }
    }

    private fun extractText(content: List<JsonObject>): String =
        content.filter { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
            .mapNotNull { it["text"]?.jsonPrimitive?.contentOrNull }
            .joinToString("\n")
            .trim()

    /**
     * 模型偶发会沿用 prompt 的格式提醒，重复 "名字：" 前缀。剥掉一次保一致。
     * 不区分大小写、不区分中英文冒号；前缀后没有正文时返回原样避免误删。
     */
    private fun stripMemberPrefix(text: String, memberName: String): String {
        if (text.isBlank() || memberName.isBlank()) return text
        val trimmed = text.trimStart()
        val separators = listOf("：", ":", " : ")
        for (sep in separators) {
            val marker = "$memberName$sep"
            if (trimmed.startsWith(marker, ignoreCase = true)) {
                return trimmed.removePrefixIgnoreCase(marker).trim()
            }
        }
        return text
    }

    private fun String.removePrefixIgnoreCase(prefix: String): String {
        if (length < prefix.length) return this
        if (!startsWith(prefix, ignoreCase = true)) return this
        return substring(prefix.length)
    }

    companion object {
        /**
         * 单个群组成员的工具循环上限。超过这个步数仍未给出纯文本就强制收尾，
         * 防止某个成员在手机操作上无限循环占用整轮时间。
         */
        private const val MEMBER_MAX_STEPS = 5
    }
}