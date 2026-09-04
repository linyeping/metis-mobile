package com.mrgreenapps.a11ypilot.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.mrgreenapps.a11ypilot.EventLog
import java.util.UUID

private val Context.automationDataStore by preferencesDataStore(name = "automation_tasks")

/** Small persistent store for scheduled agent runs. */
class AutomationRepository(private val context: Context) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        encodeDefaults = true
    }
    private val tasksKey = stringPreferencesKey("tasks_json")

    fun observeTasks(): Flow<List<AutomationTask>> = context.automationDataStore.data.map { prefs ->
        decode(prefs[tasksKey])
            .filter { it.name.isNotBlank() && it.prompt.isNotBlank() }
            .sortedWith(compareByDescending<AutomationTask> { it.enabled }.thenBy { it.nextRunAt })
    }.catch { error ->
        EventLog.append("automation> task flow failed: ${error.message}")
        emit(emptyList())
    }

    suspend fun getTask(id: String): AutomationTask? = observeTasks().first().firstOrNull { it.id == id }

    suspend fun createTask(task: AutomationTask): AutomationTask {
        val normalized = normalize(task.copy(id = task.id.ifBlank { UUID.randomUUID().toString() }))
        upsert(normalized)
        return normalized
    }

    suspend fun upsert(task: AutomationTask) {
        context.automationDataStore.edit { prefs ->
            val current = decode(prefs[tasksKey])
            val updated = current.filterNot { it.id == task.id } + normalize(task)
            prefs[tasksKey] = json.encodeToString(updated)
        }
    }

    suspend fun deleteTask(id: String) {
        context.automationDataStore.edit { prefs ->
            prefs[tasksKey] = json.encodeToString(decode(prefs[tasksKey]).filterNot { it.id == id })
        }
    }

    private fun decode(raw: String?): List<AutomationTask> = runCatching {
        raw?.let { json.decodeFromString<List<AutomationTask>>(it) }.orEmpty()
    }.getOrDefault(emptyList())

    private fun normalize(task: AutomationTask): AutomationTask {
        val normalizedModel = task.model.trim().ifBlank { ModelCatalog.defaultFor(task.provider) }
        return task.copy(
            name = task.name.trim().take(80),
            prompt = task.prompt.trim(),
            hour = task.hour.coerceIn(0, 23),
            minute = task.minute.coerceIn(0, 59),
            dayOfWeek = task.dayOfWeek.coerceIn(java.util.Calendar.SUNDAY, java.util.Calendar.SATURDAY),
            dayOfMonth = task.dayOfMonth.coerceIn(1, 31),
            month = task.month.coerceIn(1, 12),
            model = normalizedModel,
            reasoningIntensity = ReasoningCatalog.normalize(task.provider, normalizedModel, task.reasoningIntensity)
        )
    }
}
