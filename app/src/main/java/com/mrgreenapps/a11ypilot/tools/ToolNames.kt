package com.mrgreenapps.a11ypilot.tools

/**
 * 工具名权威常量表。
 *
 * 背景：工具名原本以裸字符串散落在 ToolRegistry / SafetyEvaluator / Prompts /
 * AgentEngine / PhoneUseTool 五处，拼错不会报错，只会在运行时静默失效。
 * 更糟的是三份清单会各自漂移 —— 曾经出现「Prompts 暴露给模型、AgentEngine 也实现了，
 * 但 ToolRegistry 漏注册」的工具，模型一调用就被 `isToolAvailable` 守卫拒绝，
 * 变成永远走不到的死代码（dump_diff / list_windows / dump_window /
 * read_memory / write_memory / read_notifications 都踩过这个坑）。
 *
 * 现在：
 *  - 工具名唯一来源在这里；
 *  - `ALL` 必须与 AgentEngine 分发 when 分支支持的名字一致；
 *  - 各模式的可用集合由这些常量组装，不再手写第二份清单。
 */
object ToolNames {
    // ---- 屏幕读取 ----
    const val DUMP_SCREEN = "dump_screen"
    const val DUMP_DIFF = "dump_diff"
    const val LIST_WINDOWS = "list_windows"
    const val DUMP_WINDOW = "dump_window"
    const val SCREENSHOT = "screenshot"

    // ---- 屏幕操作 ----
    const val CLICK = "click"
    const val LONG_CLICK = "long_click"
    const val SET_TEXT = "set_text"
    const val SCROLL = "scroll"
    const val TAP = "tap"
    const val SWIPE = "swipe"
    const val GLOBAL = "global"
    const val LAUNCH_APP = "launch_app"
    const val WAIT = "wait"

    // ---- 闹钟 ----
    const val SET_ALARM = "set_alarm"
    const val LIST_ALARMS = "list_alarms"
    const val CANCEL_ALARM = "cancel_alarm"
    const val CANCEL_ALL_ALARMS = "cancel_all_alarms"

    // ---- 跨应用 ----
    const val OPEN_BILIBILI_SEARCH = "open_bilibili_search"
    const val SHARE_BILIBILI_TO_WECHAT = "share_bilibili_to_wechat"
    const val SEND_WECHAT_MESSAGE = "send_wechat_message"

    // ---- 文件 ----
    const val READ_FILE = "read_file"
    const val LIST_FILES = "list_files"
    const val WRITE_FILE = "write_file"
    const val NOTEBOOK_EDIT = "notebook_edit"
    const val SHARE_FILE = "share_file"

    // ---- 命令与代码 ----
    const val RUN_COMMAND = "run_command"
    const val GREP = "grep"
    const val GLOB = "glob"
    const val GIT = "git"

    // ---- 网络 / 记忆 / 通知 ----
    const val WEB_SEARCH = "web_search"
    const val READ_MEMORY = "read_memory"
    const val WRITE_MEMORY = "write_memory"
    const val READ_NOTIFICATIONS = "read_notifications"

    // ---- 结束 ----
    const val DONE = "done"

    /** 操作用户物理设备的工具；角色卡禁用手机操作时会被整体移除。 */
    val PHONE = setOf(
        DUMP_SCREEN, DUMP_DIFF, LIST_WINDOWS, DUMP_WINDOW, SCREENSHOT,
        CLICK, LONG_CLICK, SET_TEXT, SCROLL, TAP, SWIPE, GLOBAL, LAUNCH_APP, WAIT,
        SET_ALARM, LIST_ALARMS, CANCEL_ALARM, CANCEL_ALL_ALARMS,
        OPEN_BILIBILI_SEARCH, SHARE_BILIBILI_TO_WECHAT, SEND_WECHAT_MESSAGE,
        READ_NOTIFICATIONS
    )

    /** 不产生副作用的只读工具，任何安全策略下都直接放行。 */
    val READ_ONLY = setOf(
        DUMP_SCREEN, DUMP_DIFF, SCREENSHOT,
        READ_FILE, LIST_FILES, GREP, GLOB, GIT, WEB_SEARCH, WAIT, DONE
    )

    /** 有不可逆 / 外部可见副作用的工具，需要用户确认（策略要求确认时）。 */
    val CONFIRM = setOf(
        SEND_WECHAT_MESSAGE, SHARE_BILIBILI_TO_WECHAT, SHARE_FILE,
        SET_ALARM, CANCEL_ALARM, CANCEL_ALL_ALARMS, LAUNCH_APP, GLOBAL
    )

    /** 引擎分发 when 分支支持的全部工具。新增工具必须同时登记到这里。 */
    val ALL: Set<String> = PHONE + READ_ONLY + CONFIRM + setOf(
        WRITE_FILE, NOTEBOOK_EDIT, RUN_COMMAND, READ_MEMORY, WRITE_MEMORY
    )
}
