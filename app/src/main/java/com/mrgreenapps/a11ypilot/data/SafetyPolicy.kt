package com.mrgreenapps.a11ypilot.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import com.mrgreenapps.a11ypilot.EventLog

private val Context.safetyDataStore by preferencesDataStore(name = "safety_settings")

enum class SafetyLevel(val displayName: String, val description: String) {
    STRICT(
        "严格",
        "仅读取/查看/结束任务，禁止写入、命令、手机操作、网络"
    ),
    BALANCED(
        "平衡",
        "允许日常文件与手机操作，敏感操作需确认，阻止危险命令"
    ),
    PERMISSIVE(
        "宽松",
        "允许全部工具、命令与手机操作，敏感操作不再弹窗确认"
    ),
    RESEARCH(
        "研究",
        "允许开发与研究操作，敏感操作需确认，仍阻止破坏性命令"
    )
}

data class SafetyConfig(
    val level: SafetyLevel = SafetyLevel.BALANCED,
    val blockExplicitContent: Boolean = true,
    val blockCodeExecution: Boolean = false,
    val blockFileOperations: Boolean = false,
    val blockNetworkAccess: Boolean = false,
    val requireConfirmation: Boolean = true,
    val logSensitiveActions: Boolean = true
) {
    /** Human-readable permission summary for the settings UI. */
    fun detailLines(): List<String> = buildList {
        add(when (blockCodeExecution) {
            true -> "命令/代码：禁止"
            else -> if (level == SafetyLevel.BALANCED) "命令/代码：允许，但阻止危险系统命令" else "命令/代码：允许"
        })
        add(when (blockFileOperations) {
            true -> "文件读写：禁止"
            else -> "文件读写：允许"
        })
        add(when (blockNetworkAccess) {
            true -> "网络访问：禁止"
            else -> "网络访问：允许"
        })
        add(when {
            level == SafetyLevel.STRICT -> "手机操作：禁止（仅读取屏幕）"
            else -> "手机操作：允许"
        })
        add(if (requireConfirmation) "敏感操作：弹窗确认" else "敏感操作：不弹窗，直接执行")
    }
}

object SafetySettings {
    private val KEY_SAFETY_LEVEL = stringPreferencesKey("safety_level")

    fun getSafetyLevel(context: Context): Flow<SafetyLevel> =
        context.safetyDataStore.data.map { prefs ->
            val levelName = prefs[KEY_SAFETY_LEVEL] ?: SafetyLevel.BALANCED.name
            runCatching { SafetyLevel.valueOf(levelName) }.getOrDefault(SafetyLevel.BALANCED)
        }.catch { error ->
            EventLog.append("safety> setting read failed: ${error.message}")
            emit(SafetyLevel.BALANCED)
        }

    suspend fun setSafetyLevel(context: Context, level: SafetyLevel) {
        context.safetyDataStore.edit { prefs ->
            prefs[KEY_SAFETY_LEVEL] = level.name
        }
    }

    fun getConfigForLevel(level: SafetyLevel): SafetyConfig = when (level) {
        SafetyLevel.STRICT -> SafetyConfig(
            level = level,
            blockExplicitContent = true,
            blockCodeExecution = true,
            blockFileOperations = true,
            blockNetworkAccess = true,
            requireConfirmation = true,
            logSensitiveActions = true
        )
        SafetyLevel.BALANCED -> SafetyConfig(
            level = level,
            blockExplicitContent = true,
            blockCodeExecution = false,
            blockFileOperations = false,
            blockNetworkAccess = false,
            requireConfirmation = true,
            logSensitiveActions = true
        )
        SafetyLevel.PERMISSIVE -> SafetyConfig(
            level = level,
            blockExplicitContent = false,
            blockCodeExecution = false,
            blockFileOperations = false,
            blockNetworkAccess = false,
            requireConfirmation = false,
            logSensitiveActions = true
        )
        SafetyLevel.RESEARCH -> SafetyConfig(
            level = level,
            blockExplicitContent = false,
            blockCodeExecution = false,
            blockFileOperations = false,
            blockNetworkAccess = false,
            requireConfirmation = true,
            logSensitiveActions = true
        )
    }
}
