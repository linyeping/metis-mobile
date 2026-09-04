package com.mrgreenapps.a11ypilot.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import com.mrgreenapps.a11ypilot.EventLog

private val Context.themeDataStore by preferencesDataStore(name = "theme_settings")

/**
 * Theme style options
 */
enum class AppThemeStyle(val displayName: String) {
    CLAUDE("Claude"),
    CODEX("Codex")
}

/**
 * Theme mode options
 */
enum class AppThemeMode(val displayName: String) {
    LIGHT("浅色"),
    DARK("深色"),
    SYSTEM("跟随系统")
}

enum class AppFontSize(val displayName: String, val scale: Float) {
    SMALL("较小（90%）", 0.9f),
    STANDARD("标准（100%）", 1f),
    LARGE("较大（112%）", 1.12f),
    EXTRA_LARGE("特大（125%）", 1.25f)
}

/**
 * Theme settings management
 */
object ThemeSettings {
    private val KEY_THEME_STYLE = stringPreferencesKey("theme_style")
    private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
    private val KEY_FONT_SIZE = stringPreferencesKey("font_size")
    private val KEY_FONT_SCALE = floatPreferencesKey("font_scale")

    fun getThemeStyle(context: Context): Flow<AppThemeStyle> =
        context.themeDataStore.data.map { prefs ->
            val styleName = prefs[KEY_THEME_STYLE] ?: AppThemeStyle.CLAUDE.name
            try {
                AppThemeStyle.valueOf(styleName)
            } catch (e: IllegalArgumentException) {
                AppThemeStyle.CLAUDE
            }
        }.catch { error ->
            EventLog.append("theme> style read failed: ${error.message}")
            emit(AppThemeStyle.CLAUDE)
        }

    suspend fun setThemeStyle(context: Context, style: AppThemeStyle) {
        context.themeDataStore.edit { prefs ->
            prefs[KEY_THEME_STYLE] = style.name
        }
    }

    fun getThemeMode(context: Context): Flow<AppThemeMode> =
        context.themeDataStore.data.map { prefs ->
            val modeName = prefs[KEY_THEME_MODE] ?: AppThemeMode.SYSTEM.name
            try {
                AppThemeMode.valueOf(modeName)
            } catch (e: IllegalArgumentException) {
                AppThemeMode.SYSTEM
            }
        }.catch { error ->
            EventLog.append("theme> mode read failed: ${error.message}")
            emit(AppThemeMode.SYSTEM)
        }

    suspend fun setThemeMode(context: Context, mode: AppThemeMode) {
        context.themeDataStore.edit { prefs ->
            prefs[KEY_THEME_MODE] = mode.name
        }
    }

    fun getFontSize(context: Context): Flow<AppFontSize> =
        context.themeDataStore.data.map { prefs ->
            prefs[KEY_FONT_SIZE]
                ?.let { runCatching { AppFontSize.valueOf(it) }.getOrNull() }
                ?: AppFontSize.STANDARD
        }.catch { error ->
            EventLog.append("theme> font size read failed: ${error.message}")
            emit(AppFontSize.STANDARD)
        }

    suspend fun setFontSize(context: Context, size: AppFontSize) {
        context.themeDataStore.edit {
            it[KEY_FONT_SIZE] = size.name
            it[KEY_FONT_SCALE] = size.scale
        }
    }

    /** Continuous UI scale used by the slider in settings. */
    fun getFontScale(context: Context): Flow<Float> =
        context.themeDataStore.data.map { prefs ->
            prefs[KEY_FONT_SCALE]
                ?: prefs[KEY_FONT_SIZE]?.let { value ->
                    runCatching { AppFontSize.valueOf(value).scale }.getOrNull()
                }
                ?: AppFontSize.STANDARD.scale
        }.catch { error ->
            EventLog.append("theme> font scale read failed: ${error.message}")
            emit(AppFontSize.STANDARD.scale)
        }

    suspend fun setFontScale(context: Context, scale: Float) {
        val normalized = scale.coerceIn(0.8f, 1.4f)
        context.themeDataStore.edit { it[KEY_FONT_SCALE] = normalized }
    }
}
