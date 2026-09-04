package com.mrgreenapps.a11ypilot.data

/**
 * Agent mode with associated thinking state and tools
 */
enum class AgentMode(
    val displayName: String,
    val displayNameZh: String,
    val description: String,
    val descriptionZh: String,
    val defaultThinkingState: ThinkingState
) {
    CHAT(
        displayName = "Chat",
        displayNameZh = "聊天",
        description = "Conversation and simple code execution",
        descriptionZh = "对话和简单代码执行",
        defaultThinkingState = ThinkingState.UNDERSTANDING
    ),
    COWORK(
        displayName = "Cowork",
        displayNameZh = "协作",
        description = "File operations, writing, and phone automation",
        descriptionZh = "文件操作、写作和手机自动化",
        defaultThinkingState = ThinkingState.WORKING
    ),
    CODE(
        displayName = "Code",
        displayNameZh = "编程",
        description = "Full development environment with terminal access",
        descriptionZh = "完整的开发环境和终端访问",
        defaultThinkingState = ThinkingState.EXECUTING
    );

    fun getDisplayName(useZh: Boolean = true): String {
        return if (useZh) displayNameZh else displayName
    }

    fun getDescription(useZh: Boolean = true): String {
        return if (useZh) descriptionZh else description
    }

    /**
     * Map tool execution to appropriate thinking state
     */
    fun getThinkingStateForTool(toolName: String): ThinkingState {
        return when {
            // File operations
            toolName.contains("file", ignoreCase = true) ||
            toolName.contains("read", ignoreCase = true) ||
            toolName.contains("write", ignoreCase = true) -> ThinkingState.RETRIEVING

            // Code execution
            toolName.contains("code", ignoreCase = true) ||
            toolName.contains("execute", ignoreCase = true) ||
            toolName.contains("terminal", ignoreCase = true) ||
            toolName.contains("bash", ignoreCase = true) -> ThinkingState.EXECUTING

            // Phone automation
            toolName.contains("tap", ignoreCase = true) ||
            toolName.contains("swipe", ignoreCase = true) ||
            toolName.contains("launch", ignoreCase = true) ||
            toolName.contains("app", ignoreCase = true) -> ThinkingState.EXECUTING

            // Analysis and retrieval
            toolName.contains("search", ignoreCase = true) ||
            toolName.contains("find", ignoreCase = true) ||
            toolName.contains("get", ignoreCase = true) -> ThinkingState.RETRIEVING

            // Output and generation
            toolName.contains("send", ignoreCase = true) ||
            toolName.contains("message", ignoreCase = true) ||
            toolName.contains("generate", ignoreCase = true) -> ThinkingState.ORGANIZING

            // Default to mode's default state
            else -> defaultThinkingState
        }
    }

    /**
     * Check if currently compacting context
     */
    fun isCompacting(): Boolean {
        return false // Will be set by AgentEngine when compaction occurs
    }
}
