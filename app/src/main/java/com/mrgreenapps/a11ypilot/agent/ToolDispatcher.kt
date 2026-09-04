package com.mrgreenapps.a11ypilot.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.mrgreenapps.a11ypilot.EventLog
import com.mrgreenapps.a11ypilot.tools.DocumentTool
import com.mrgreenapps.a11ypilot.tools.TermuxCommandRunner
import com.mrgreenapps.a11ypilot.tools.ToolNames
import com.mrgreenapps.a11ypilot.phoneuse.AlarmStore
import com.mrgreenapps.a11ypilot.phoneuse.PhoneUseTool
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 把 AgentEngine 里散落的「工具名 → 真实调用」分支抽出来。
 *
 * 设计动机：原来的 AgentEngine.kt 同时承担「状态机 / 摘要持久化 / 提示词装配 / 工具调用」四件事，
 * 单文件 700+ 行；现在 [ToolDispatcher] 负责工具分发，[AgentEngine] 负责会话循环。
 *
 * 职责：
 *  1. 把模型返回的 tool name + input 翻译成具体执行（屏幕动作、文件、Termux、网络、记忆等）。
 *  2. 统一处理 dispatch 阶段的异常：永远返回 [ToolExecutor.Result.Err]，永不向上抛。
 *  3. 维护会话期间生成的附件清单（write_file / notebook_edit 写入的文件路径）。
 *
 * 不负责：
 *  - 安全策略（由 SafetyEvaluator 决定 Allow/Block/Confirm）。
 *  - 重试（由 AgentEngine.runLoop 调用 RetryPolicy）。
 *  - 状态机（_state、toolHistory 仍在 AgentEngine 内）。
 */
class ToolDispatcher(
    private val appContext: Context,
    private val executor: ToolExecutor,
    private val documentTool: DocumentTool,
    private val termuxCommandRunner: TermuxCommandRunner,
) {
    /** 会话期间生成的文件绝对路径列表（write_file / notebook_edit 写入）。 */
    private val generatedFiles: MutableList<String> = mutableListOf()

    /** 当前会话写入过哪些文件（只读快照）。 */
    val generatedFilesSnapshot: List<String> get() = generatedFiles.toList()

    /** 重置：新一轮会话开始时清掉附件清单。 */
    fun resetGeneratedFiles() = generatedFiles.clear()

    /**
     * 读取跨会话持久记忆。供 [AgentEngine] 在装配 system prompt 时调用，不通过 dispatch 入口。
     * 失败时返回带错误前缀的中文消息，AgentEngine 会跳过注入（避免把内部异常直接喂给模型）。
     */
    fun readPersistentMemory(): String = readMemory()

    /** 工具分发的入口：返回 Result.Err 而不是抛异常，便于上层统一处理。 */
    suspend fun dispatch(name: String, input: JsonObject): ToolExecutor.Result {
        fun int(key: String, default: Int? = null): Int =
            input[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                ?: default
                ?: throw IllegalArgumentException("Missing int '$key'")
        fun str(key: String, default: String? = null): String =
            input[key]?.jsonPrimitive?.contentOrNull
                ?: default
                ?: throw IllegalArgumentException("Missing string '$key'")
        fun bool(key: String, default: Boolean? = null): Boolean =
            input[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                ?: default
                ?: throw IllegalArgumentException("Missing bool '$key'")
        return try {
            when (name) {
                ToolNames.DUMP_SCREEN -> executor.dumpScreen()
                ToolNames.DUMP_DIFF -> executor.dumpDiff()
                ToolNames.LIST_WINDOWS -> executor.listWindows()
                ToolNames.DUMP_WINDOW -> executor.dumpWindow(int("window_id"))
                ToolNames.SCREENSHOT -> executor.screenshot()
                ToolNames.CLICK -> executor.click(int("id"))
                ToolNames.LONG_CLICK -> executor.longClick(int("id"))
                ToolNames.SET_TEXT -> executor.setText(int("id"), str("value"))
                ToolNames.SCROLL -> executor.scroll(int("id"), str("direction"))
                ToolNames.TAP -> executor.tap(int("x"), int("y"))
                ToolNames.SWIPE -> executor.swipe(int("x1"), int("y1"), int("x2"), int("y2"), int("duration_ms", 300).toLong())
                ToolNames.GLOBAL -> executor.global(str("action"))
                ToolNames.LAUNCH_APP -> executor.launchApp(str("package"), bool("background", true))
                ToolNames.WAIT -> executor.wait(int("ms"))
                ToolNames.SET_ALARM -> setAlarm(
                    hour = int("hour"),
                    minute = int("minute"),
                    message = str("message", "Metis 闹钟")
                )
                ToolNames.LIST_ALARMS -> listAlarms()
                ToolNames.CANCEL_ALARM -> cancelAlarm(str("id"))
                ToolNames.CANCEL_ALL_ALARMS -> cancelAllAlarms()
                ToolNames.OPEN_BILIBILI_SEARCH -> PhoneUseTool.searchBilibiliVideo(str("query")).fold(
                    onSuccess = { textResult(it) },
                    onFailure = { ToolExecutor.Result.Err(it.message ?: "哔哩哔哩后台操作失败") }
                )
                ToolNames.SHARE_BILIBILI_TO_WECHAT -> PhoneUseTool.shareLatestBilibiliToWechat(
                    query = str("query"),
                    contact = str("contact")
                ).fold(
                    onSuccess = { textResult(it) },
                    onFailure = { ToolExecutor.Result.Err(it.message ?: "跨应用分享失败") }
                )
                ToolNames.SEND_WECHAT_MESSAGE -> PhoneUseTool.sendWechatMessage(
                    contact = str("contact"),
                    message = str("message")
                ).fold(
                    onSuccess = { textResult(it) },
                    onFailure = { ToolExecutor.Result.Err(it.message ?: "微信操作失败") }
                )
                ToolNames.READ_FILE -> textResult(documentTool.read(str("path")))
                ToolNames.LIST_FILES -> textResult(documentTool.list(str("path", "")))
                ToolNames.WRITE_FILE -> {
                    val result = documentTool.write(
                        path = str("path"),
                        content = str("content"),
                        format = str("format", "")
                    )
                    generatedFiles += result.file.absolutePath
                    textResult("${result.description}\n附件路径：${result.file.absolutePath}")
                }
                ToolNames.RUN_COMMAND -> termuxCommandRunner.run(str("command")).fold(
                    onSuccess = { textResult(it.displayText()) },
                    onFailure = { ToolExecutor.Result.Err(it.message ?: "Termux 命令执行失败") }
                )
                ToolNames.GREP -> runTermux("rg --no-heading --line-number --hidden --glob '!.git' ${shellQuote(str("pattern"))} ${shellQuote(str("path"))}")
                ToolNames.GLOB -> runTermux("fd --hidden --exclude .git --type f ${shellQuote(str("pattern"))} ${shellQuote(str("path"))}")
                ToolNames.GIT -> {
                    val operation = str("operation")
                    require(operation in setOf("status", "diff", "log", "branch")) { "仅支持只读 Git 操作" }
                    runTermux("git -C ${shellQuote(str("path"))} $operation")
                }
                ToolNames.NOTEBOOK_EDIT -> {
                    val result = documentTool.write(str("path"), str("content"), "ipynb")
                    generatedFiles += result.file.absolutePath
                    textResult("${result.description}\n附件路径：${result.file.absolutePath}")
                }
                ToolNames.WEB_SEARCH -> webSearch(str("query"))
                ToolNames.SHARE_FILE -> shareFile(str("path"), str("package"))
                ToolNames.READ_MEMORY -> textResult(readMemory())
                ToolNames.WRITE_MEMORY -> writeMemory(str("content"))
                ToolNames.READ_NOTIFICATIONS -> textResult(readNotifications())
                ToolNames.DONE -> executor.done(bool("success"), str("summary"))
                else -> ToolExecutor.Result.Err("unknown tool: $name")
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            EventLog.append("dispatch [$name] threw ${t.javaClass.simpleName}: ${t.message}")
            ToolExecutor.Result.Err("${t.javaClass.simpleName}: ${t.message ?: "dispatch error"}")
        }
    }

    // ===== 私有辅助方法 =====

    private fun textResult(text: String): ToolExecutor.Result.Ok = ToolExecutor.Result.Ok(
        screen = text,
        foregroundApp = "Metis"
    )

    private suspend fun runTermux(command: String): ToolExecutor.Result {
        return termuxCommandRunner.run(command).fold(
            onSuccess = { textResult(it.displayText()) },
            onFailure = { ToolExecutor.Result.Err(it.message ?: "Termux 命令执行失败") }
        )
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun readMemory(): String {
        if (!memoryFile.exists()) return "（记忆为空）"
        return runCatching { memoryFile.readText(Charsets.UTF_8).take(16_000) }
            .onFailure { EventLog.append("dispatch> read_memory failed: ${it.javaClass.simpleName}: ${it.message}") }
            .getOrElse { "（记忆读取失败：${it.javaClass.simpleName}: ${it.message ?: "未知错误"}）" }
    }

    private fun writeMemory(content: String): ToolExecutor.Result {
        val trimmed = content.trim().take(16_000)
        return runCatching {
            memoryFile.writeText(trimmed, Charsets.UTF_8)
            textResult("已更新持久记忆（${trimmed.length} 字符）。以后每次会话开始都会自动加载这些内容。")
        }.getOrElse { ToolExecutor.Result.Err("写入记忆失败：${it.message}") }
    }

    private fun readNotifications(): String {
        if (!com.mrgreenapps.a11ypilot.MetisNotificationListener.isConnected()) {
            return "（通知监听未开启：请在系统设置 > 通知使用权中授权 Metis）"
        }
        return com.mrgreenapps.a11ypilot.MetisNotificationListener.recentText()
    }

    private fun webSearch(query: String): ToolExecutor.Result {
        val uri = Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")
        return runCatching {
            appContext.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            textResult("已在当前屏幕打开网页搜索：$query")
        }.getOrElse { error ->
            EventLog.append("dispatch> web_search failed: ${error.javaClass.simpleName}: ${error.message}")
            ToolExecutor.Result.Err("无法打开系统浏览器（${error.javaClass.simpleName}: ${error.message ?: "未知错误"}）")
        }
    }

    private fun shareFile(path: String, packageName: String): ToolExecutor.Result {
        val file = documentTool.resolve(path)
        require(file.isFile) { "文件不存在：$path" }
        val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = when (file.extension.lowercase()) {
                "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                "pdf" -> "application/pdf"
                else -> "text/plain"
            }
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            if (packageName.isNotBlank()) setPackage(packageName)
        }
        return runCatching {
            appContext.startActivity(
                Intent.createChooser(intent, "分享 ${file.name}")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            textResult("已在当前屏幕打开分享面板：${file.name}；请在目标应用中确认发送。")
        }.getOrElse { error ->
            EventLog.append("dispatch> share_file failed: ${error.javaClass.simpleName}: ${error.message}")
            ToolExecutor.Result.Err("无法打开文件分享面板：${file.name}（${error.javaClass.simpleName}: ${error.message ?: "未知错误"}）")
        }
    }

    private suspend fun setAlarm(hour: Int, minute: Int, message: String): ToolExecutor.Result {
        return AlarmStore.schedule(appContext, hour, minute, message).fold(
            onSuccess = { alarm ->
                textResult("已确认登记 Metis 闹钟：%02d:%02d（id=%s）%s".format(
                    alarm.hour, alarm.minute, alarm.id, alarm.message.takeIf { it.isNotBlank() }?.let { "，备注：$it" } ?: ""
                ))
            },
            onFailure = { ToolExecutor.Result.Err(it.message ?: "设置闹钟失败") }
        )
    }

    private suspend fun listAlarms(): ToolExecutor.Result {
        val alarms = AlarmStore.list(appContext)
        if (alarms.isEmpty()) return textResult("当前没有可取消的 Metis 闹钟。")
        val lines = alarms.sortedBy { it.triggerAtMillis }.joinToString("\n") { alarm ->
            val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(alarm.triggerAtMillis))
            "- $time · id=${alarm.id}" + alarm.message.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
        }
        return textResult("当前已登记 ${alarms.size} 个 Metis 闹钟：\n$lines")
    }

    private suspend fun cancelAlarm(id: String): ToolExecutor.Result =
        AlarmStore.cancel(appContext, id).fold(
            onSuccess = { textResult("已确认取消 Metis 闹钟：$id") },
            onFailure = { ToolExecutor.Result.Err(it.message ?: "取消闹钟失败") }
        )

    private suspend fun cancelAllAlarms(): ToolExecutor.Result =
        AlarmStore.cancelAll(appContext).fold(
            onSuccess = { textResult("已确认取消全部 Metis 闹钟，共 $it 个。") },
            onFailure = { ToolExecutor.Result.Err(it.message ?: "取消全部闹钟失败") }
        )

    private val memoryFile: java.io.File by lazy {
        java.io.File(appContext.filesDir, "metis_memory.md")
    }

    companion object {
        /**
         * 给 [GroupCoordinator] 单成员回合使用的 dispatcher 工厂。
         *
         * 群组成员的工具循环是独立于 AgentEngine 主循环的次级调度，与主 AgentEngine 实例
         * 没有状态共享需求；但为了与 [SafetyEvaluator] / 工具注册表保持一致，这里仍然复用
         * 同一套 ToolExecutor / DocumentTool / TermuxCommandRunner。
         *
         * 注意：不持有任何持久化副作用（addMessage / writeFile attachments 都不写入主仓库，
         * 仅走 DocumentTool.write 把文件落盘到 Metis 工作区，群组附件清单也由
         * AgentEngine.runGroupLoop 在 Completed 事件中按 toolSummary 决定是否合并）。
         */
        fun createForGroup(appContext: Context): ToolDispatcher {
            val ctx = appContext.applicationContext
            val executor = ToolExecutor(ctx, excludeOwnPackage = false)
            return ToolDispatcher(
                appContext = ctx,
                executor = executor,
                documentTool = DocumentTool(ctx),
                termuxCommandRunner = TermuxCommandRunner(ctx)
            )
        }
    }
}
