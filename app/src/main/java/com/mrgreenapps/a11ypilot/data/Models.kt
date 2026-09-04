package com.mrgreenapps.a11ypilot.data

import kotlinx.serialization.Serializable

enum class WorkMode {
    CHAT,      // 普通对话
    COWORK,    // 协作模式
    CODE       // 代码模式
}

enum class ModelProvider(val displayName: String) {
    /** Legacy value retained only so previously serialized sessions can be read and migrated. */
    CUSTOM_CLAUDE("Claude 中转"),
    CUSTOM_OPENAI("GPT 中转"),
    DEEPSEEK("DeepSeek 官方")
}

enum class ReasoningIntensity(val apiValue: String) {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    XHIGH("xhigh"),
    MAX("max")
}

enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}

enum class MessageStatus {
    IN_PROGRESS,
    COMPLETE,
    ERROR
}

// ThinkingState moved to ThinkingState.kt

/**
 * 会话实体
 */
@Serializable
data class Session(
    val id: String,
    val title: String,
    val mode: WorkMode,
    val provider: ModelProvider = ModelProvider.CUSTOM_OPENAI,
    val model: String,
    val reasoningIntensity: ReasoningIntensity,
    val createdAt: Long,
    val lastActiveAt: Long,
    val isArchived: Boolean = false,
    val isPinned: Boolean = false,
    val safetyLevel: SafetyLevel = SafetyLevel.BALANCED,
    val characterCardId: String? = null,
    /**
     * 会话级摘要。每次完成一轮工具任务时由 AgentEngine 写入，下次启动会话时
     * 会先注入这段摘要，再注入截取的近期历史，让跨长周期的会话保留「上次干到哪」的事实。
     * 留空表示尚未生成摘要。
     */
    val summary: String? = null,
    /**
     * 最近一次工具任务的工具调用数（成功 / 失败由 toolCalls 列表决定）。
     * 用于在 UI 摘要里展示「上轮做了 N 步」。
     */
    val lastRunSteps: Int = 0,
    val lastRunAt: Long = 0,
    /**
     * 群组成员角色卡 id 列表（仅群组会话填充）。
     * 用于 WorkScreen 顶部 sticky banner 与会话抽屉「群组对话 · N 人」徽章，
     * 不依赖运行时再解析消息流，回放也照样能识别。
     */
    val groupMemberIds: List<String> = emptyList()
)

/**
 * 消息实体
 */
@Serializable
data class Message(
    val id: String,
    val sessionId: String,
    val role: MessageRole,
    val content: String,
    val timestamp: Long,
    val status: MessageStatus = MessageStatus.COMPLETE,
    val metadata: String? = null,
    val thinkingState: ThinkingState? = null,  // Current thinking state
    val toolCalls: List<ToolCall>? = null,     // Tool invocations
    val contextTokens: Int = 0,                 // Current context size
    val isCompacting: Boolean = false,          // Compaction in progress
    val attachments: List<String>? = null,      // File paths for attachments
    /**
     * 群组发言人 id：只有由 GroupCoordinator 生成的 assistant 消息才会填，对应
     * [com.mrgreenapps.a11ypilot.agent.CharacterCard.id]。普通单成员/无群聊的对话
     * 该字段始终为 null。持久化为单独字段，不挤进 [metadata]，便于按发言人检索。
     */
    val speakerId: String? = null,
    /**
     * 群组发言人显示名（角色卡名），冗余存储是避免历史回放时还得再查 CharacterCard。
     * UI 渲染与按发言人分组都优先用此字段。
     */
    val speakerName: String? = null
)

/**
 * 工具调用记录
 */
@Serializable
data class ToolCall(
    val name: String,
    val timestamp: Long,
    val status: String = "running"
)
