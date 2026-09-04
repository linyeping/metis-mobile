package com.mrgreenapps.a11ypilot.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mrgreenapps.a11ypilot.agent.AgentSettings
import com.mrgreenapps.a11ypilot.EventLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.apiKeyDataStore by preferencesDataStore(name = "api_key_profiles")

@Serializable
enum class ApiKeyProfileProvider(val label: String) {
    RELAY("API 中转"),
    DEEPSEEK("DeepSeek 官方"),
    CUSTOM("自定义 API")
}

@Serializable
enum class ApiKeyTestStatus {
    NEVER_TESTED,
    TESTING,
    SUCCESS,
    FAILED
}

@Serializable
enum class ApiKeyBalanceStatus {
    NEVER_FETCHED,
    FETCHING,
    SUCCESS,
    FAILED
}

@Serializable
data class ApiKeyProfileRecord(
    val id: String,
    val label: String,
    val provider: ApiKeyProfileProvider = ApiKeyProfileProvider.CUSTOM,
    val baseUrl: String,
    val note: String = "",
    val models: List<String> = emptyList(),
    val status: ApiKeyTestStatus = ApiKeyTestStatus.NEVER_TESTED,
    val statusMessage: String = "",
    val testedAt: Long? = null,
    val balanceStatus: ApiKeyBalanceStatus = ApiKeyBalanceStatus.NEVER_FETCHED,
    val balanceAmount: String? = null,
    val balanceCurrency: String? = null,
    val balanceMessage: String = "",
    val balanceUpdatedAt: Long? = null
)

data class ApiKeyProfile(
    val record: ApiKeyProfileRecord,
    val apiKey: String
) {
    val id: String get() = record.id
    val label: String get() = record.label
    val provider: ApiKeyProfileProvider get() = record.provider
    val baseUrl: String get() = record.baseUrl
    val note: String get() = record.note
    val models: List<String> get() = record.models
    val status: ApiKeyTestStatus get() = record.status
    val statusMessage: String get() = record.statusMessage
    val testedAt: Long? get() = record.testedAt
    val balanceStatus: ApiKeyBalanceStatus get() = record.balanceStatus
    val balanceAmount: String? get() = record.balanceAmount
    val balanceCurrency: String? get() = record.balanceCurrency
    val balanceMessage: String get() = record.balanceMessage
    val balanceUpdatedAt: Long? get() = record.balanceUpdatedAt
}

object ApiKeyRepository {
    private val key = stringPreferencesKey("profiles_json")
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        encodeDefaults = true
    }

    fun observeProfiles(context: Context): Flow<List<ApiKeyProfile>> =
        context.apiKeyDataStore.data.map { prefs ->
            val records = decode(prefs[key])
            records.map { record -> ApiKeyProfile(record, AgentSettings.profileApiKey(context, record.id)) }
        }.catch { error ->
            EventLog.append("apikey> profile flow failed: ${error.message}")
            emit(emptyList())
        }

    suspend fun ensureSeeded(context: Context) {
        val existing = observeRecords(context).first()
        val baseUrl = AgentSettings.baseUrl(context).first()
        val defaults = listOf(
            ApiKeyProfileRecord(
                id = "relay",
                label = "API 中转",
                provider = ApiKeyProfileProvider.RELAY,
                baseUrl = baseUrl
            ),
            ApiKeyProfileRecord(
                id = "deepseek",
                label = "DeepSeek 官方",
                provider = ApiKeyProfileProvider.DEEPSEEK,
                baseUrl = AgentSettings.DEEPSEEK_BASE_URL
            )
        )
        // SettingsScreen historically stored keys in AgentSettings while the router now reads
        // profile-scoped secrets. Reconcile both stores on every startup, including installs
        // that already have an older, empty profile list.
        val merged = defaults.map { seed ->
            val stored = existing.firstOrNull { it.id == seed.id }
            stored?.copy(baseUrl = stored.baseUrl.ifBlank { seed.baseUrl }) ?: seed
        } + existing.filterNot { item -> defaults.any { it.id == item.id } }
        val legacyKeys = mapOf(
            "relay" to AgentSettings.gptApiKey(context),
            "deepseek" to AgentSettings.deepseekApiKey(context)
        )
        legacyKeys.forEach { (id, legacyKey) ->
            if (legacyKey.isNotBlank() && AgentSettings.profileApiKey(context, id).isBlank()) {
                AgentSettings.setProfileApiKey(context, id, legacyKey)
            }
        }
        // 把旧 profile id="pinaic" 的 key 升级到 "relay"，避免丢用户已配过的中转 key。
        if (existing.none { it.id == "relay" }) {
            val legacyPinaic = AgentSettings.profileApiKey(context, "pinaic")
            if (legacyPinaic.isNotBlank()) {
                AgentSettings.setProfileApiKey(context, "relay", legacyPinaic)
            }
        }
        if (existing != merged || existing.isEmpty()) saveRecords(context, merged)
    }

    suspend fun save(context: Context, profile: ApiKeyProfileRecord, apiKey: String) {
        val normalized = profile.copy(
            label = profile.label.trim().ifBlank { "未命名 API" },
            baseUrl = profile.baseUrl.trim().trimEnd('/'),
            note = profile.note.trim()
        )
        AgentSettings.setProfileApiKey(context, normalized.id, apiKey)
        when (normalized.provider) {
            ApiKeyProfileProvider.RELAY -> {
                AgentSettings.setGptApiKey(context, apiKey)
                AgentSettings.setBaseUrl(context, normalized.baseUrl)
            }
            ApiKeyProfileProvider.DEEPSEEK -> AgentSettings.setDeepseekApiKey(context, apiKey)
            ApiKeyProfileProvider.CUSTOM -> Unit
        }
        val records = observeRecords(context).first().upsert(normalized)
        saveRecords(context, records)
    }

    suspend fun delete(context: Context, id: String) {
        AgentSettings.clearProfileApiKey(context, id)
        when (id) {
            "relay" -> AgentSettings.setGptApiKey(context, "")
            "deepseek" -> AgentSettings.setDeepseekApiKey(context, "")
        }
        saveRecords(context, observeRecords(context).first().filterNot { it.id == id })
    }

    suspend fun updateProbe(
        context: Context,
        id: String,
        status: ApiKeyTestStatus,
        models: List<String> = emptyList(),
        message: String = ""
    ) {
        val records = observeRecords(context).first().map { record ->
            if (record.id != id) record else record.copy(
                status = status,
                models = models,
                statusMessage = message,
                testedAt = if (status == ApiKeyTestStatus.SUCCESS || status == ApiKeyTestStatus.FAILED) {
                    System.currentTimeMillis()
                } else record.testedAt
            )
        }
        saveRecords(context, records)
    }

    suspend fun updateBalance(
        context: Context,
        id: String,
        status: ApiKeyBalanceStatus,
        amount: String? = null,
        currency: String? = null,
        message: String = ""
    ) {
        val records = observeRecords(context).first().map { record ->
            if (record.id != id) record else record.copy(
                balanceStatus = status,
                balanceAmount = amount ?: if (status == ApiKeyBalanceStatus.SUCCESS) null else record.balanceAmount,
                balanceCurrency = currency ?: if (status == ApiKeyBalanceStatus.SUCCESS) null else record.balanceCurrency,
                balanceMessage = message,
                balanceUpdatedAt = if (status == ApiKeyBalanceStatus.SUCCESS || status == ApiKeyBalanceStatus.FAILED) {
                    System.currentTimeMillis()
                } else record.balanceUpdatedAt
            )
        }
        saveRecords(context, records)
    }

    suspend fun createCustom(context: Context): ApiKeyProfileRecord {
        val profile = ApiKeyProfileRecord(
            id = "custom-${UUID.randomUUID()}",
            label = "新 API 密钥",
            provider = ApiKeyProfileProvider.CUSTOM,
            baseUrl = AgentSettings.DEFAULT_RELAY_BASE_URL
        )
        save(context, profile, "")
        return profile
    }

    private fun observeRecords(context: Context): Flow<List<ApiKeyProfileRecord>> =
        context.apiKeyDataStore.data.map { prefs -> decode(prefs[key]) }
            .catch { error ->
                EventLog.append("apikey> record flow failed: ${error.message}")
                emit(emptyList())
            }

    private suspend fun saveRecords(context: Context, records: List<ApiKeyProfileRecord>) {
        context.apiKeyDataStore.edit { it[key] = json.encodeToString(records) }
    }

    private fun decode(value: String?): List<ApiKeyProfileRecord> =
        value?.let { runCatching { json.decodeFromString<List<ApiKeyProfileRecord>>(it) }.getOrNull() }
            .orEmpty()

    private fun List<ApiKeyProfileRecord>.upsert(item: ApiKeyProfileRecord): List<ApiKeyProfileRecord> =
        if (any { it.id == item.id }) map { if (it.id == item.id) item else it } else this + item
}
