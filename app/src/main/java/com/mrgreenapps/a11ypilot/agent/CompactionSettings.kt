package com.mrgreenapps.a11ypilot.agent

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.compactionDataStore by preferencesDataStore(name = "compaction_settings")

data class CompactionConfig(
    val enabled: Boolean = true,
    val maxContextTokens: Int = 372_000,
    val triggerThreshold: Int = 316_200, // 85% of max
    val minThreshold: Int = 50_000,
    val pauseAfterCompaction: Boolean = false,
    val customInstructions: String? = null
)

object CompactionSettings {
    private val KEY_MAX_TOKENS = intPreferencesKey("max_context_tokens")
    private val KEY_TRIGGER_THRESHOLD = intPreferencesKey("trigger_threshold")

    fun getMaxContextTokens(context: Context): Flow<Int> =
        context.compactionDataStore.data.map { prefs ->
            prefs[KEY_MAX_TOKENS] ?: 372_000
        }

    suspend fun setMaxContextTokens(context: Context, tokens: Int) {
        context.compactionDataStore.edit { prefs ->
            prefs[KEY_MAX_TOKENS] = tokens.coerceIn(50_000, 500_000)
        }
    }

    fun getTriggerThreshold(context: Context): Flow<Int> =
        context.compactionDataStore.data.map { prefs ->
            prefs[KEY_TRIGGER_THRESHOLD] ?: 316_200 // 85%
        }

    suspend fun setTriggerThreshold(context: Context, tokens: Int) {
        context.compactionDataStore.edit { prefs ->
            prefs[KEY_TRIGGER_THRESHOLD] = tokens.coerceIn(50_000, 450_000)
        }
    }

    fun getCompactionConfig(context: Context): Flow<CompactionConfig> =
        context.compactionDataStore.data.map { prefs ->
            val maxTokens = prefs[KEY_MAX_TOKENS] ?: 372_000
            val triggerThreshold = prefs[KEY_TRIGGER_THRESHOLD] ?: 316_200
            CompactionConfig(
                maxContextTokens = maxTokens,
                triggerThreshold = triggerThreshold
            )
        }
}
