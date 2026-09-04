package com.mrgreenapps.a11ypilot.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.UUID
import com.mrgreenapps.a11ypilot.EventLog

private val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore(name = "sessions")

/**
 * Repository for managing conversation sessions.
 * Uses DataStore for persistence.
 */
class SessionRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        encodeDefaults = true
    }

    private val KEY_SESSIONS = stringPreferencesKey("sessions_json")
    private val KEY_LEGACY_ACTIVE_SESSION = stringPreferencesKey("active_session_id")
    private val KEY_UNIFIED_ACTIVE_SESSION = stringPreferencesKey("unified_active_session_id")
    private val KEY_MESSAGES_PREFIX = "messages_"

    /**
     * Flow of all sessions (excluding archived).
     */
    fun observeSessions(): Flow<List<Session>> = context.sessionDataStore.data.map { prefs ->
        val jsonStr = prefs[KEY_SESSIONS] ?: "[]"
        decodeSessions(jsonStr)
            .map(::normalizeSession)
            .filter { !it.isArchived }
            .sortedWith(compareByDescending<Session> { it.isPinned }.thenByDescending { it.lastActiveAt })
    }.catch { error ->
        EventLog.append("session> observe flow failed: ${error.message}")
        emit(emptyList())
    }

    /**
     * Get sessions for a specific mode.
     */
    fun observeSessionsByMode(mode: WorkMode): Flow<List<Session>> =
        observeSessions().map { sessions ->
            sessions.filter { it.mode == mode }
        }

    /**
     * Get the currently active session ID.
     */
    fun observeActiveSessionId(mode: WorkMode): Flow<String?> = context.sessionDataStore.data.map { prefs ->
        prefs[activeSessionKey(mode)] ?: prefs[KEY_LEGACY_ACTIVE_SESSION]?.takeIf { legacyId ->
            val sessions = prefs[KEY_SESSIONS]?.let {
                decodeSessions(it)
            }.orEmpty()
            sessions.any { it.id == legacyId && it.mode == mode && !it.isArchived }
        }
    }.catch { error ->
        EventLog.append("session> active flow failed: ${error.message}")
        emit(null)
    }

    /** Active conversation for the single Metis workspace, independent of legacy modes. */
    fun observeUnifiedActiveSessionId(): Flow<String?> = context.sessionDataStore.data.map { prefs ->
        val sessions = prefs[KEY_SESSIONS]?.let {
            decodeSessions(it)
        }.orEmpty().filterNot { it.isArchived }
        prefs[KEY_UNIFIED_ACTIVE_SESSION]?.takeIf { id -> sessions.any { it.id == id } }
            ?: sessions.maxByOrNull { it.lastActiveAt }?.id
    }.catch { error ->
        EventLog.append("session> unified active flow failed: ${error.message}")
        emit(null)
    }

    /**
     * Create a new session.
     */
    suspend fun createSession(
        mode: WorkMode,
        provider: ModelProvider = ModelProvider.CUSTOM_OPENAI,
        model: String,
        reasoningIntensity: ReasoningIntensity = ReasoningIntensity.MEDIUM,
        safetyLevel: SafetyLevel = SafetyLevel.BALANCED,
        title: String = "新对话",
        makeActive: Boolean = true,
        characterCardId: String? = null,
        groupMemberIds: List<String> = emptyList()
    ): Session {
        val now = System.currentTimeMillis()
        val session = Session(
            id = UUID.randomUUID().toString(),
            title = title,
            mode = mode,
            provider = provider,
            model = model,
            reasoningIntensity = reasoningIntensity,
            createdAt = now,
            lastActiveAt = now,
            safetyLevel = safetyLevel,
            characterCardId = characterCardId,
            groupMemberIds = groupMemberIds
        )

        context.sessionDataStore.edit { prefs ->
            val existing = prefs[KEY_SESSIONS]?.let {
                decodeSessions(it)
            } ?: emptyList()
            val updated = existing + session
            prefs[KEY_SESSIONS] = json.encodeToString(updated)
            if (makeActive) {
                prefs[activeSessionKey(mode)] = session.id
                prefs[KEY_UNIFIED_ACTIVE_SESSION] = session.id
            }
        }

        return session
    }

    /**
     * Get a specific session by ID.
     */
    suspend fun getSession(id: String): Session? {
        val sessions = observeSessions().first()
        return sessions.find { it.id == id }
    }

    /**
     * Update an existing session.
     */
    suspend fun updateSession(session: Session) {
        val normalizedSession = normalizeSession(session)
        context.sessionDataStore.edit { prefs ->
            val existing = prefs[KEY_SESSIONS]?.let {
                decodeSessions(it)
            } ?: emptyList()
            val updated = existing.map { if (it.id == normalizedSession.id) normalizedSession else it }
            prefs[KEY_SESSIONS] = json.encodeToString(updated)
        }
    }

    /**
     * Set the active session.
     */
    suspend fun setActiveSession(id: String) {
        context.sessionDataStore.edit { prefs ->
            val sessions = prefs[KEY_SESSIONS]?.let {
                decodeSessions(it)
            } ?: emptyList()
            val selected = sessions.firstOrNull { it.id == id } ?: return@edit
            prefs[activeSessionKey(selected.mode)] = id
            prefs[KEY_UNIFIED_ACTIVE_SESSION] = id
            val updated = sessions.map {
                if (it.id == id) it.copy(lastActiveAt = System.currentTimeMillis())
                else it
            }
            prefs[KEY_SESSIONS] = json.encodeToString(updated)
        }
    }

    /** Delete a session and its locally persisted message history. */
    suspend fun deleteSession(id: String) {
        context.sessionDataStore.edit { prefs ->
            val sessions = prefs[KEY_SESSIONS]?.let {
                decodeSessions(it)
            } ?: emptyList()
            val updated = sessions.filterNot { it.id == id }
            prefs[KEY_SESSIONS] = json.encodeToString(updated)
            prefs.remove(stringPreferencesKey(KEY_MESSAGES_PREFIX + id))

            WorkMode.entries.forEach { mode ->
                val key = activeSessionKey(mode)
                if (prefs[key] == id) prefs.remove(key)
            }
            if (prefs[KEY_LEGACY_ACTIVE_SESSION] == id) prefs.remove(KEY_LEGACY_ACTIVE_SESSION)
            if (prefs[KEY_UNIFIED_ACTIVE_SESSION] == id) prefs.remove(KEY_UNIFIED_ACTIVE_SESSION)
        }
    }

    suspend fun setUnifiedActiveSession(id: String) {
        context.sessionDataStore.edit { prefs ->
            val sessions = prefs[KEY_SESSIONS]?.let(::decodeSessions).orEmpty()
            val selected = sessions.firstOrNull { it.id == id && !it.isArchived } ?: return@edit
            prefs[KEY_UNIFIED_ACTIVE_SESSION] = selected.id
            prefs[activeSessionKey(selected.mode)] = selected.id
            prefs[KEY_SESSIONS] = json.encodeToString(
                sessions.map { if (it.id == selected.id) it.copy(lastActiveAt = System.currentTimeMillis()) else it }
            )
        }
    }

    suspend fun renameSession(id: String, title: String) {
        val normalized = title.trim().replace(Regex("\\s+"), " ").take(40)
        if (normalized.isBlank()) return
        getSession(id)?.let { updateSession(it.copy(title = normalized)) }
    }

    /**
     * 写入或更新一个会话的运行摘要。
     *
     * 摘要由 AgentEngine 在每次工具任务完成（State.Done / State.Error）时生成，长度上限 1200
     * 字符；旧摘要会被新摘要覆盖。摘要用于下次启动同一会话时给模型一个「上次干到哪」的最小事实集合，
     * 避免「写个文档发给微信」这种多轮跟进完全丢失上下文。
     */
    suspend fun updateSessionSummary(
        id: String,
        summary: String,
        lastRunSteps: Int,
        lastRunAt: Long = System.currentTimeMillis()
    ) {
        val normalized = summary.trim().take(1200)
        if (normalized.isBlank() && lastRunSteps == 0) return
        getSession(id)?.let { existing ->
            updateSession(
                existing.copy(
                    summary = normalized.takeIf { it.isNotBlank() },
                    lastRunSteps = lastRunSteps,
                    lastRunAt = lastRunAt
                )
            )
        }
    }

    /** 清掉一个会话的摘要（例如用户手动清空历史时）。 */
    suspend fun clearSessionSummary(id: String) {
        getSession(id)?.let { updateSession(it.copy(summary = null, lastRunSteps = 0, lastRunAt = 0)) }
    }

    suspend fun setPinned(id: String, pinned: Boolean) {
        getSession(id)?.let { updateSession(it.copy(isPinned = pinned)) }
    }

    /**
     * Apply the global model choice to the automatically-created empty session in
     * each mode. Sessions with messages (or a user-created title) remain independent.
     */
    suspend fun syncEmptyDefaultSessions(provider: ModelProvider, model: String) {
        context.sessionDataStore.edit { prefs ->
            val sessions = prefs[KEY_SESSIONS]?.let(::decodeSessions) ?: emptyList()
            val activeIds = WorkMode.entries.mapNotNull { mode ->
                prefs[activeSessionKey(mode)]
            }.toSet()
            val changed = sessions.map { session ->
                if (session.id in activeIds && !session.isArchived && isDefaultEmptySession(prefs, session)) {
                    session.copy(
                        provider = provider,
                        model = model,
                        reasoningIntensity = ReasoningCatalog.defaultFor(provider, model)
                    )
                } else session
            }
            prefs[KEY_SESSIONS] = json.encodeToString(changed)
        }
    }

    private fun isDefaultEmptySession(prefs: androidx.datastore.preferences.core.Preferences, session: Session): Boolean {
        val key = stringPreferencesKey(KEY_MESSAGES_PREFIX + session.id)
        val messages = prefs[key].orEmpty()
        if (messages.isNotBlank() && messages != "[]") return false
        return session.title == "${session.mode.titleZh()}会话 1" ||
            session.title.matches(Regex("${session.mode.titleZh()}会话 \\d+"))
    }

    /**
     * Get messages for a session.
     */
    suspend fun getMessages(sessionId: String): List<Message> {
        val key = stringPreferencesKey(KEY_MESSAGES_PREFIX + sessionId)
        val prefs = runCatching { context.sessionDataStore.data.first() }.getOrElse {
            EventLog.append("session> message read failed: ${it.message}")
            return emptyList()
        }
        val jsonStr = prefs[key] ?: "[]"
        return decodeMessages(jsonStr)
    }

    /** Observe all persisted messages for usage statistics and local search. */
    fun observeAllMessages(): Flow<List<Message>> = context.sessionDataStore.data.map { prefs ->
        prefs.asMap()
            .filterKeys { it.name.startsWith(KEY_MESSAGES_PREFIX) }
            .values
            .flatMap { value ->
                value.toString().let { raw ->
                    decodeMessages(raw)
                }
            }
            .sortedBy { it.timestamp }
    }.catch { error ->
        EventLog.append("session> message flow failed: ${error.message}")
        emit(emptyList())
    }

    /**
     * Add a message to a session.
     */
    suspend fun addMessage(message: Message) {
        val key = stringPreferencesKey(KEY_MESSAGES_PREFIX + message.sessionId)
        context.sessionDataStore.edit { prefs ->
            val existing = prefs[key]?.let {
                decodeMessages(it)
            } ?: emptyList()
            val updated = existing + message
            prefs[key] = json.encodeToString(updated)

            // Update session's lastActiveAt
            val sessions = prefs[KEY_SESSIONS]?.let {
                decodeSessions(it)
            } ?: emptyList()
            val updatedSessions = sessions.map {
                if (it.id == message.sessionId)
                    it.copy(lastActiveAt = System.currentTimeMillis())
                else it
            }
            prefs[KEY_SESSIONS] = json.encodeToString(updatedSessions)
        }
    }

    /**
     * Update an existing message.
     */
    suspend fun updateMessage(message: Message) {
        val key = stringPreferencesKey(KEY_MESSAGES_PREFIX + message.sessionId)
        context.sessionDataStore.edit { prefs ->
            val existing = prefs[key]?.let {
                decodeMessages(it)
            } ?: emptyList()
            val updated = existing.map { if (it.id == message.id) message else it }
            prefs[key] = json.encodeToString(updated)
        }
    }

    /** Remove a message and every later message, preserving the earlier transcript. */
    suspend fun deleteMessagesFrom(sessionId: String, messageId: String) {
        val key = stringPreferencesKey(KEY_MESSAGES_PREFIX + sessionId)
        context.sessionDataStore.edit { prefs ->
            val existing = prefs[key]?.let {
                decodeMessages(it)
            } ?: emptyList()
            val index = existing.indexOfFirst { it.id == messageId }
            if (index >= 0) prefs[key] = json.encodeToString(existing.take(index))
        }
    }

    /** Remove all messages after the given message while keeping that message. */
    suspend fun deleteMessagesAfter(sessionId: String, messageId: String) {
        val key = stringPreferencesKey(KEY_MESSAGES_PREFIX + sessionId)
        context.sessionDataStore.edit { prefs ->
            val existing = prefs[key]?.let {
                decodeMessages(it)
            } ?: emptyList()
            val index = existing.indexOfFirst { it.id == messageId }
            if (index >= 0) prefs[key] = json.encodeToString(existing.take(index + 1))
        }
    }

    private fun activeSessionKey(mode: WorkMode) =
        stringPreferencesKey("active_session_${mode.name.lowercase()}_id")

    private fun normalizeSession(session: Session): Session {
        if (session.provider == ModelProvider.CUSTOM_CLAUDE) {
            val model = ModelCatalog.defaultFor(ModelProvider.CUSTOM_OPENAI)
            return session.copy(
                provider = ModelProvider.CUSTOM_OPENAI,
                model = model,
                reasoningIntensity = ReasoningCatalog.defaultFor(ModelProvider.CUSTOM_OPENAI, model)
            )
        }
        if (session.provider == ModelProvider.DEEPSEEK &&
            (session.model.equals("deepseek-chat", ignoreCase = true) || session.model.startsWith("deepseek-v3", ignoreCase = true))) {
            val model = ModelCatalog.defaultFor(ModelProvider.DEEPSEEK)
            return session.copy(model = model, reasoningIntensity = ReasoningCatalog.defaultFor(ModelProvider.DEEPSEEK, model))
        }
        return session
    }
}

private fun WorkMode.titleZh(): String = when (this) {
    WorkMode.CHAT -> "聊天"
    WorkMode.COWORK -> "协作"
    WorkMode.CODE -> "编程"
}

private val repositoryJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
    encodeDefaults = true
}

/** Decode old and partially damaged records without taking down the settings screen. */
private fun decodeSessions(raw: String): List<Session> {
    val direct = runCatching { repositoryJson.decodeFromString<List<Session>>(raw) }.getOrNull()
    if (direct != null) return direct
    val array = runCatching { repositoryJson.parseToJsonElement(raw).jsonArray }.getOrNull() ?: run {
        EventLog.append("session> invalid sessions JSON ignored")
        return emptyList()
    }
    return array.mapNotNull { element ->
        runCatching { repositoryJson.decodeFromJsonElement<Session>(element) }.getOrNull()
            ?: decodeLegacySession(element)
    }
}

private fun decodeLegacySession(element: kotlinx.serialization.json.JsonElement): Session? {
    val obj = element as? JsonObject ?: return null
    val now = System.currentTimeMillis()
    val provider = enumValue<ModelProvider>(obj, "provider") ?: ModelProvider.CUSTOM_OPENAI
    val model = obj.stringValue("model")?.ifBlank { null } ?: ModelCatalog.defaultFor(provider)
    val mode = enumValue<WorkMode>(obj, "mode") ?: WorkMode.CHAT
    val reasoning = enumValue<ReasoningIntensity>(obj, "reasoningIntensity")
        ?: ReasoningCatalog.defaultFor(provider, model)
    val id = obj.stringValue("id")?.ifBlank { null } ?: UUID.randomUUID().toString()
    return Session(
        id = id,
        title = obj.stringValue("title")?.ifBlank { null } ?: "${mode.titleZh()}会话",
        mode = mode,
        provider = provider,
        model = model,
        reasoningIntensity = reasoning,
        createdAt = obj.longValue("createdAt") ?: now,
        lastActiveAt = obj.longValue("lastActiveAt") ?: obj.longValue("updatedAt") ?: now,
        isArchived = obj.boolValue("isArchived") ?: false,
        isPinned = obj.boolValue("isPinned") ?: false,
        safetyLevel = enumValue<SafetyLevel>(obj, "safetyLevel") ?: SafetyLevel.BALANCED,
        characterCardId = obj.stringValue("characterCardId"),
        // 老会话没有群组成员清单字段：decodeFromString 路径走 repositoryJson 的
        // ignoreUnknownKeys 默认忽略；本路径手工读，没有就保持空列表，与默认值一致。
        groupMemberIds = (obj["groupMemberIds"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?: emptyList()
    )
}

private fun decodeMessages(raw: String): List<Message> {
    val direct = runCatching { repositoryJson.decodeFromString<List<Message>>(raw) }.getOrNull()
    if (direct != null) return direct
    val array = runCatching { repositoryJson.parseToJsonElement(raw).jsonArray }.getOrNull() ?: run {
        EventLog.append("session> invalid message JSON ignored")
        return emptyList()
    }
    return array.mapNotNull { element ->
        runCatching { repositoryJson.decodeFromJsonElement<Message>(element) }.getOrNull()
            ?: decodeLegacyMessage(element)
    }
}

private fun decodeLegacyMessage(element: kotlinx.serialization.json.JsonElement): Message? {
    val obj = element as? JsonObject ?: return null
    val role = enumValue<MessageRole>(obj, "role") ?: return null
    val sessionId = obj.stringValue("sessionId") ?: return null
    val content = obj.stringValue("content") ?: ""
    val now = System.currentTimeMillis()
    return Message(
        id = obj.stringValue("id")?.ifBlank { null } ?: UUID.randomUUID().toString(),
        sessionId = sessionId,
        role = role,
        content = content,
        timestamp = obj.longValue("timestamp") ?: now,
        status = enumValue<MessageStatus>(obj, "status") ?: MessageStatus.COMPLETE,
        metadata = obj.stringValue("metadata"),
        thinkingState = enumValue<ThinkingState>(obj, "thinkingState"),
        contextTokens = obj.intValue("contextTokens") ?: 0,
        isCompacting = obj.boolValue("isCompacting") ?: false,
        attachments = null,
        // 早期记录里没有群组发言人字段，decoder 默认填 null。speakerId/speakerName
        // 由 GroupCoordinator 在写入新消息时显式赋值，老消息保持 null 表示非群组回复。
        speakerId = obj.stringValue("speakerId"),
        speakerName = obj.stringValue("speakerName")
    )
}

private inline fun <reified T : Enum<T>> enumValue(obj: JsonObject, key: String): T? =
    obj.stringValue(key)?.let { raw ->
        enumValues<T>().firstOrNull { it.name.equals(raw, ignoreCase = true) }
    }

private fun JsonObject.stringValue(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.longValue(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull
private fun JsonObject.intValue(key: String): Int? = longValue(key)?.toInt()
private fun JsonObject.boolValue(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull
