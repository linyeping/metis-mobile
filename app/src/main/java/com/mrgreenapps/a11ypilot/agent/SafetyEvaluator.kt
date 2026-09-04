package com.mrgreenapps.a11ypilot.agent

import com.mrgreenapps.a11ypilot.data.SafetyConfig
import com.mrgreenapps.a11ypilot.data.SafetyLevel
import com.mrgreenapps.a11ypilot.tools.ToolNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Enforces the selected session policy immediately before a tool is dispatched. */
object SafetyEvaluator {
    sealed class Decision {
        data object Allow : Decision()
        data class Block(val reason: String) : Decision()
        /** Requires explicit user approval before the tool is dispatched. */
        data class Confirm(val tool: String, val summary: String) : Decision()
    }

    // 工具集合统一取自 ToolNames，避免这里再维护一份会漂移的副本。
    private val readOnlyTools = ToolNames.READ_ONLY
    private val confirmTools = ToolNames.CONFIRM
    private val fileTools = setOf(
        ToolNames.WRITE_FILE, ToolNames.READ_FILE, ToolNames.LIST_FILES, ToolNames.SHARE_FILE
    )
    private val networkTools = setOf(ToolNames.WEB_SEARCH, ToolNames.RUN_COMMAND)
    private val highRiskCommand = Regex(
        """(?i)(^|[;&|]\s*)(sudo\b|su\b|rm\s+-[^\n]*r|mkfs\b|dd\s+if=|reboot\b|shutdown\b|poweroff\b|format\b)|(/system|/data)(/|\b)|chmod\s+777|curl\b[^\n]*\|\s*(sh|bash)|wget\b[^\n]*\|\s*(sh|bash)"""
    )
    private val balancedCommand = Regex(
        """(?i)\b(adb|pm\s+(uninstall|disable|clear)|am\s+force-stop|settings\s+put|mount|iptables|reg\s+(add|delete)|del\s+/[qsf]|erase\b)\b"""
    )

    /**
     * Evaluate a tool call against the full [config]. This is the authoritative gate:
     * unlike the legacy level-only path, it honors [SafetyConfig.requireConfirmation] so a
     * PERMISSIVE / RESEARCH policy no longer pops a confirmation dialog.
     */
    fun evaluate(config: SafetyConfig, tool: String, input: JsonObject): Decision {
        if (tool in readOnlyTools) return Decision.Allow
        if (config.blockCodeExecution && tool == ToolNames.RUN_COMMAND) {
            return Decision.Block("${config.level.displayName}策略禁止执行代码/命令")
        }
        if (config.blockFileOperations && tool in fileTools) {
            return Decision.Block("${config.level.displayName}策略禁止文件操作")
        }
        if (config.blockNetworkAccess && tool in networkTools) {
            return Decision.Block("${config.level.displayName}策略禁止网络访问")
        }
        if (config.level == SafetyLevel.STRICT) {
            return Decision.Block("严格策略仅允许读取、查看和结束任务；已阻止 $tool")
        }
        // Confirmation gate for irreversible actions — only when the policy asks for it.
        if (config.requireConfirmation && tool in confirmTools) {
            return Decision.Confirm(tool, confirmSummary(tool, input))
        }
        if (tool != ToolNames.RUN_COMMAND) return Decision.Allow

        val command = input["command"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (config.level != SafetyLevel.PERMISSIVE && highRiskCommand.containsMatchIn(command)) {
            return Decision.Block("${config.level.displayName}策略阻止了高风险系统命令")
        }
        if (config.level == SafetyLevel.BALANCED && balancedCommand.containsMatchIn(command)) {
            return Decision.Block("平衡策略阻止了会修改设备或应用状态的系统命令")
        }
        return Decision.Allow
    }

    /** Back-compat overload kept for callers that only hold a level. */
    fun evaluate(level: SafetyLevel, tool: String, input: JsonObject): Decision =
        evaluate(com.mrgreenapps.a11ypilot.data.SafetySettings.getConfigForLevel(level), tool, input)

    private fun confirmSummary(tool: String, input: JsonObject): String {
        val arg = when (tool) {
            ToolNames.SEND_WECHAT_MESSAGE -> input["contact"]?.jsonPrimitive?.contentOrNull
                ?.let { "发给「$it」" }.orEmpty()
            ToolNames.SHARE_BILIBILI_TO_WECHAT -> input["contact"]?.jsonPrimitive?.contentOrNull
                ?.let { "分享给「$it」" }.orEmpty()
            ToolNames.SHARE_FILE -> input["path"]?.jsonPrimitive?.contentOrNull ?: ""
            ToolNames.LAUNCH_APP -> input["package"]?.jsonPrimitive?.contentOrNull ?: ""
            ToolNames.GLOBAL -> input["action"]?.jsonPrimitive?.contentOrNull ?: ""
            ToolNames.SET_ALARM -> "设置闹钟"
            ToolNames.CANCEL_ALARM -> "取消闹钟"
            ToolNames.CANCEL_ALL_ALARMS -> "取消全部闹钟"
            else -> ""
        }
        return listOfNotNull(tool, arg).joinToString(" ")
    }
}
