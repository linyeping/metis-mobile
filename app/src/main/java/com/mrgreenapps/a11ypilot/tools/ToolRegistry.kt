package com.mrgreenapps.a11ypilot.tools

import com.mrgreenapps.a11ypilot.data.ThinkingState
import com.mrgreenapps.a11ypilot.data.WorkMode

/** The executable tools exposed to the model in each of the three product modes. */
object ToolRegistry {
    /** The unified workspace lets the model choose the right capability per request. */
    val CHAT_MODE_TOOLS: Set<String> = ToolNames.ALL

    val COWORK_MODE_TOOLS: Set<String> = ToolNames.ALL

    val CODE_MODE_TOOLS: Set<String> = setOf(
        ToolNames.READ_FILE, ToolNames.WRITE_FILE, ToolNames.LIST_FILES,
        ToolNames.GREP, ToolNames.GLOB, ToolNames.GIT, ToolNames.NOTEBOOK_EDIT,
        ToolNames.SHARE_FILE, ToolNames.RUN_COMMAND, ToolNames.WEB_SEARCH,
        ToolNames.DONE
    )

    fun getToolsForMode(mode: WorkMode): Set<String> = when (mode) {
        WorkMode.CHAT -> CHAT_MODE_TOOLS
        WorkMode.COWORK -> COWORK_MODE_TOOLS
        WorkMode.CODE -> CODE_MODE_TOOLS
    }

    fun getToolsForMode(mode: String): Set<String> = runCatching {
        getToolsForMode(WorkMode.valueOf(mode.uppercase()))
    }.getOrDefault(CHAT_MODE_TOOLS)

    fun isToolAvailable(tool: String, mode: WorkMode): Boolean =
        tool in getToolsForMode(mode)

    fun isToolAvailable(tool: String, mode: String): Boolean =
        tool in getToolsForMode(mode)

    fun thinkingStateFor(mode: WorkMode, toolName: String?): ThinkingState {
        if (toolName == null) return when (mode) {
            WorkMode.CHAT -> ThinkingState.UNDERSTANDING
            WorkMode.COWORK -> ThinkingState.WORKING
            WorkMode.CODE -> ThinkingState.EXECUTING
        }
        return when (toolName) {
            ToolNames.DUMP_SCREEN, ToolNames.SCREENSHOT, ToolNames.READ_FILE,
            ToolNames.LIST_FILES -> ThinkingState.RETRIEVING

            ToolNames.CLICK, ToolNames.LONG_CLICK, ToolNames.SET_TEXT, ToolNames.SCROLL,
            ToolNames.TAP, ToolNames.SWIPE, ToolNames.GLOBAL, ToolNames.LAUNCH_APP,
            ToolNames.WAIT, ToolNames.SET_ALARM, ToolNames.LIST_ALARMS,
            ToolNames.CANCEL_ALARM, ToolNames.CANCEL_ALL_ALARMS,
            ToolNames.OPEN_BILIBILI_SEARCH, ToolNames.SHARE_BILIBILI_TO_WECHAT,
            ToolNames.SEND_WECHAT_MESSAGE, ToolNames.WRITE_FILE,
            ToolNames.NOTEBOOK_EDIT, ToolNames.RUN_COMMAND, ToolNames.GIT,
            ToolNames.SHARE_FILE -> ThinkingState.EXECUTING

            ToolNames.GREP, ToolNames.GLOB, ToolNames.WEB_SEARCH -> ThinkingState.RETRIEVING

            ToolNames.DONE -> ThinkingState.ORGANIZING
            else -> ThinkingState.THINKING
        }
    }
}
