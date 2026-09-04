package com.mrgreenapps.a11ypilot.widget

import android.content.Context
import com.mrgreenapps.a11ypilot.agent.AgentEngine

/**
 * 桌面小组件的任务状态存储。
 *
 * 小组件运行在桌面 Launcher 进程中，无法直接读取 Agent 进程内的 StateFlow，
 * 因此 AgentExecutionService 在状态变化时把一份轻量快照写到这里（SharedPreferences 底层是
 * 进程共享的文件），小组件通过 onUpdate 读取并渲染。
 */
object WidgetStatusStore {
    private const val PREFS = "widget_status"
    private const val KEY_RUNNING = "running"
    private const val KEY_STEP = "step"
    private const val KEY_MAX_STEPS = "max_steps"
    private const val KEY_LAST = "last"
    private const val KEY_DONE_TEXT = "done_text"
    private const val KEY_UPDATED_AT = "updated_at"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 从 AgentEngine.State 生成快照并写入（由服务调用）。 */
    fun write(context: Context, state: AgentEngine.State) {
        val p = prefs(context).edit()
        when (state) {
            AgentEngine.State.Idle -> {
                p.putBoolean(KEY_RUNNING, false)
                p.putString(KEY_LAST, "空闲")
            }
            is AgentEngine.State.Running -> {
                p.putBoolean(KEY_RUNNING, true)
                p.putInt(KEY_STEP, state.step)
                p.putInt(KEY_MAX_STEPS, state.maxSteps)
                p.putString(KEY_LAST, state.last)
            }
            is AgentEngine.State.Done -> {
                p.putBoolean(KEY_RUNNING, false)
                p.putString(KEY_DONE_TEXT, if (state.success) "任务已完成" else "任务未完成")
                p.putString(KEY_LAST, if (state.success) "任务已完成" else "任务未完成")
            }
            is AgentEngine.State.Error -> {
                p.putBoolean(KEY_RUNNING, false)
                p.putString(KEY_DONE_TEXT, state.message)
                p.putString(KEY_LAST, "出错：${state.message}")
            }
        }
        p.putLong(KEY_UPDATED_AT, System.currentTimeMillis())
        p.apply()
    }

    data class Status(
        val running: Boolean,
        val step: Int,
        val maxSteps: Int,
        val last: String
    )

    fun read(context: Context): Status {
        val p = prefs(context)
        return Status(
            running = p.getBoolean(KEY_RUNNING, false),
            step = p.getInt(KEY_STEP, 0),
            maxSteps = p.getInt(KEY_MAX_STEPS, 0),
            last = p.getString(KEY_LAST, "空闲").orEmpty()
        )
    }
}
