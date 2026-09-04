package com.mrgreenapps.a11ypilot.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.mrgreenapps.a11ypilot.EventLog

private val Context.usageDataStore by preferencesDataStore(name = "usage_ledger")

@Serializable
data class UsageEntry(
    val timestamp: Long,
    val sessionId: String,
    val provider: ModelProvider,
    val model: String,
    val inputTokens: Int,
    val cachedInputTokens: Int,
    val outputTokens: Int
) {
    val totalTokens: Long get() = inputTokens.toLong() + cachedInputTokens + outputTokens
}

object UsageRepository {
    private val key = stringPreferencesKey("entries_json")
    private val budgetKey = stringPreferencesKey("monthly_budget_tokens")
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        encodeDefaults = true
    }
    private val mutex = Mutex()

    fun observe(context: Context): Flow<List<UsageEntry>> = context.usageDataStore.data.map { prefs ->
        prefs[key]?.let { runCatching { json.decodeFromString<List<UsageEntry>>(it) }.getOrNull() }.orEmpty()
    }.catch { error ->
        EventLog.append("usage> ledger flow failed: ${error.message}")
        emit(emptyList())
    }

    suspend fun record(context: Context, entry: UsageEntry) = mutex.withLock {
        val current = observe(context).first()
        val updated = (current + entry).takeLast(MAX_ENTRIES)
        context.usageDataStore.edit { it[key] = json.encodeToString(updated) }
    }

    suspend fun clear(context: Context) = mutex.withLock {
        context.usageDataStore.edit { it.remove(key) }
    }

    /** Aggregate token totals grouped by session id for the given time window. */
    suspend fun sessionTotals(context: Context, sinceMillis: Long): Map<String, Long> {
        return observe(context).first()
            .filter { it.timestamp >= sinceMillis }
            .groupBy { it.sessionId }
            .mapValues { (_, list) -> list.sumOf { it.totalTokens } }
    }

    /** Aggregate token totals grouped by model for the given time window. */
    suspend fun modelTotals(context: Context, sinceMillis: Long): Map<String, Long> {
        return observe(context).first()
            .filter { it.timestamp >= sinceMillis }
            .groupBy { it.model }
            .mapValues { (_, list) -> list.sumOf { it.totalTokens } }
    }

    /** Aggregate token totals grouped by provider for the given time window. */
    suspend fun providerTotals(context: Context, sinceMillis: Long): Map<ModelProvider, Long> {
        return observe(context).first()
            .filter { it.timestamp >= sinceMillis }
            .groupBy { it.provider }
            .mapValues { (_, list) -> list.sumOf { it.totalTokens } }
    }

    /** Monthly budget in tokens; 0 means no budget set. */
    suspend fun budget(context: Context): Long =
        context.usageDataStore.data.first()[budgetKey]?.toLongOrNull() ?: 0L

    suspend fun setBudget(context: Context, tokens: Long) {
        context.usageDataStore.edit { it[budgetKey] = tokens.toString() }
    }

    /** Tokens consumed in the current calendar month. */
    suspend fun currentMonthTokens(context: Context): Long {
        val start = monthStartMillis()
        return observe(context).first()
            .filter { it.timestamp >= start }
            .sumOf { it.totalTokens }
    }

    /** Remaining budget for the month; Long.MAX_VALUE when no budget is set. */
    suspend fun remainingBudget(context: Context): Long {
        val budget = budget(context)
        if (budget <= 0L) return Long.MAX_VALUE
        return (budget - currentMonthTokens(context)).coerceAtLeast(0L)
    }

    private fun monthStartMillis(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private const val MAX_ENTRIES = 2000
}
