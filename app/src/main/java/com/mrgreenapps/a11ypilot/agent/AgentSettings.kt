package com.mrgreenapps.a11ypilot.agent

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import com.mrgreenapps.a11ypilot.data.ModelCatalog
import com.mrgreenapps.a11ypilot.data.ModelProvider
import com.mrgreenapps.a11ypilot.EventLog

private val Context.agentDataStore by preferencesDataStore(name = "agent_settings")

object AgentSettings {
    private val KEY_GPT_API_KEY = stringPreferencesKey("gpt_api_key")
    private val KEY_DEEPSEEK_API_KEY = stringPreferencesKey("deepseek_api_key")
    private val KEY_BASE_URL = stringPreferencesKey("base_url")
    private val KEY_MODEL = stringPreferencesKey("model")
    private val KEY_DEFAULT_PROVIDER = stringPreferencesKey("default_provider")
    private val KEY_MAX_STEPS = intPreferencesKey("max_steps")
    private val KEY_MCP_ENABLED = booleanPreferencesKey("mcp_enabled")
    private val KEY_MCP_PORT = intPreferencesKey("mcp_port")
    private val KEY_MCP_TOKEN = stringPreferencesKey("mcp_token")
    private val KEY_MCP_RELAY_URL = stringPreferencesKey("mcp_relay_url")
    private val KEY_MCP_RELAY_TOKEN = stringPreferencesKey("mcp_relay_token")
    private val KEY_MAX_CONTEXT_TOKENS = intPreferencesKey("max_context_tokens")
    private val KEY_COMPACTION_THRESHOLD = intPreferencesKey("compaction_threshold")
    private val KEY_DEEPSEEK_MODELS = stringPreferencesKey("deepseek_models")
    private val KEY_GPT_MODELS = stringPreferencesKey("gpt_models")
    private val KEY_PERSONA_PRESET = stringPreferencesKey("persona_preset")
    private val KEY_PERSONA_INSTRUCTION = stringPreferencesKey("persona_instruction")
    private val KEY_API_KEY_ROTATION_STRATEGY = stringPreferencesKey("api_key_rotation_strategy")
    private val KEY_RETRY_ENABLED = booleanPreferencesKey("retry_enabled")
    private val KEY_RETRY_ATTEMPTS = intPreferencesKey("retry_attempts")
    private val KEY_PROFILE_NICKNAME = stringPreferencesKey("profile_nickname")
    private val KEY_PROFILE_EMAIL = stringPreferencesKey("profile_email")
    private val KEY_PROFILE_AVATAR_URI = stringPreferencesKey("profile_avatar_uri")
    private val KEY_CHARACTER_CARDS = stringPreferencesKey("character_cards")
    private val KEY_ACTIVE_CHARACTER_ID = stringPreferencesKey("active_character_id")
    private val KEY_DRAWER_COLLAPSED = stringPreferencesKey("drawer_collapsed_sections")
    private val KEY_NOTIF_TITLE = stringPreferencesKey("notif_title")
    private val KEY_NOTIF_RUNNING_TEMPLATE = stringPreferencesKey("notif_running_template")
    private val KEY_NOTIF_COMPLETE_TEXT = stringPreferencesKey("notif_complete_text")

    // NOTE: Base URLs are intentionally empty so first-run users supply their own
    // endpoint instead of inheriting a private relay. Legacy installs (with
    // api.pinaic.com written to settings) are upgraded to "" by baseUrl() below.
    const val DEFAULT_BASE_URL = ""
    const val DEFAULT_MODEL = "gpt-4o-mini"
    const val DEFAULT_MAX_STEPS = 50
    const val DEFAULT_MCP_PORT = 8765
    const val DEFAULT_MAX_CONTEXT_TOKENS = 372_000
    const val DEFAULT_COMPACTION_THRESHOLD = 316_200 // 85% of 372K
    const val DEFAULT_RETRY_ENABLED = true
    const val DEFAULT_RETRY_ATTEMPTS = 10
    const val DEFAULT_RELAY_BASE_URL = ""
    const val DEEPSEEK_BASE_URL = "https://api.deepseek.com"
    const val PERSONA_PRESET_FRIENDLY = "friendly"
    const val PERSONA_PRESET_PRACTICAL = "practical"
    const val DEFAULT_PERSONA_INSTRUCTION = ""
    private const val LEGACY_DEFAULT_BASE_URL = "https://api.anthropic.com"

    private const val SECRETS_FILE = "agent_secrets"

    private fun secrets(context: Context) = EncryptedSharedPreferences.create(
        context,
        SECRETS_FILE,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private fun readSecret(context: Context, key: String): String {
        val encrypted = secrets(context).getString(key, null)
        if (encrypted != null) return encrypted
        val legacy = context.getSharedPreferences("agent_prefs", Context.MODE_PRIVATE)
            .getString(key, "").orEmpty()
        if (legacy.isNotEmpty()) {
            secrets(context).edit().putString(key, legacy).apply()
            context.getSharedPreferences("agent_prefs", Context.MODE_PRIVATE).edit().remove(key).apply()
        }
        return legacy
    }

    private fun writeSecret(context: Context, key: String, value: String) {
        secrets(context).edit().putString(key, value).apply()
        context.getSharedPreferences("agent_prefs", Context.MODE_PRIVATE).edit().remove(key).apply()
    }

    fun deepseekApiKey(context: Context): String {
        return readSecret(context, KEY_DEEPSEEK_API_KEY.name)
    }

    fun gptApiKey(context: Context): String = readSecret(context, KEY_GPT_API_KEY.name)

    /** Profile-scoped secrets used by the API key management screen. */
    fun profileApiKey(context: Context, profileId: String): String =
        readSecret(context, "profile_api_key_${profileId.trim()}")

    suspend fun setDeepseekApiKey(context: Context, key: String) {
        context.agentDataStore.edit { it.remove(KEY_DEEPSEEK_API_KEY) }
        writeSecret(context, KEY_DEEPSEEK_API_KEY.name, key)
    }

    suspend fun setGptApiKey(context: Context, key: String) {
        context.agentDataStore.edit { it.remove(KEY_GPT_API_KEY) }
        writeSecret(context, KEY_GPT_API_KEY.name, key)
    }

    suspend fun setProfileApiKey(context: Context, profileId: String, key: String) {
        writeSecret(context, "profile_api_key_${profileId.trim()}", key.trim())
    }

    suspend fun clearProfileApiKey(context: Context, profileId: String) {
        val name = "profile_api_key_${profileId.trim()}"
        secrets(context).edit().remove(name).apply()
        context.getSharedPreferences("agent_prefs", Context.MODE_PRIVATE).edit().remove(name).apply()
    }

    fun baseUrl(context: Context): Flow<String> =
        context.agentDataStore.data.map { prefs ->
            normalizeRelayBaseUrl(prefs[KEY_BASE_URL])
        }.recoverSettings("baseUrl", DEFAULT_RELAY_BASE_URL)

    suspend fun setBaseUrl(context: Context, url: String) {
        context.agentDataStore.edit { it[KEY_BASE_URL] = url }
    }

    fun model(context: Context): Flow<String> =
        context.agentDataStore.data.map { prefs ->
            normalizeStoredModel(prefs[KEY_MODEL], prefs[KEY_DEFAULT_PROVIDER])
        }.recoverSettings("model", DEFAULT_MODEL)

    suspend fun setModel(context: Context, model: String) {
        context.agentDataStore.edit { it[KEY_MODEL] = model }
    }

    /** Persist the provider catalog returned by the official DeepSeek probe. */
    suspend fun setDeepseekModels(context: Context, models: List<String>) {
        val normalized = ModelCatalog.normalizeDeepSeekModels(models)
        context.agentDataStore.edit { prefs ->
            if (normalized.isEmpty()) prefs.remove(KEY_DEEPSEEK_MODELS)
            else prefs[KEY_DEEPSEEK_MODELS] = normalized.joinToString("\n")
        }
    }

    fun deepseekModels(context: Context): Flow<List<String>> =
        context.agentDataStore.data.map { prefs ->
            prefs[KEY_DEEPSEEK_MODELS]
                ?.lineSequence()
                ?.map(String::trim)
                ?.filter(String::isNotBlank)
                ?.distinct()
                ?.sorted()
                ?.toList()
                .orEmpty()
        }.recoverSettings("deepseekModels", emptyList())

    suspend fun setGptModels(context: Context, models: List<String>) {
        context.agentDataStore.edit { prefs ->
            if (models.isEmpty()) prefs.remove(KEY_GPT_MODELS) else prefs[KEY_GPT_MODELS] = models.distinct().sorted().joinToString("\n")
        }
    }

    fun gptModels(context: Context): Flow<List<String>> = context.agentDataStore.data.map { prefs ->
        prefs[KEY_GPT_MODELS]?.lineSequence()?.map(String::trim)?.filter(String::isNotBlank)?.distinct()?.sorted()?.toList().orEmpty()
    }.recoverSettings("gptModels", emptyList())

    fun personaPreset(context: Context): Flow<String> = context.agentDataStore.data.map { prefs ->
        prefs[KEY_PERSONA_PRESET] ?: PERSONA_PRESET_FRIENDLY
    }.recoverSettings("personaPreset", PERSONA_PRESET_FRIENDLY)

    fun personaInstruction(context: Context): Flow<String> = context.agentDataStore.data.map { prefs ->
        prefs[KEY_PERSONA_INSTRUCTION] ?: DEFAULT_PERSONA_INSTRUCTION
    }.recoverSettings("personaInstruction", DEFAULT_PERSONA_INSTRUCTION)

    fun apiKeyRotationStrategy(context: Context): Flow<ApiKeyRouter.Strategy> =
        context.agentDataStore.data.map { prefs ->
            prefs[KEY_API_KEY_ROTATION_STRATEGY]
                ?.let { runCatching { ApiKeyRouter.Strategy.valueOf(it) }.getOrNull() }
                ?: ApiKeyRouter.Strategy.HEALTH_FIRST
        }.recoverSettings("apiKeyRotationStrategy", ApiKeyRouter.Strategy.HEALTH_FIRST)

    suspend fun setApiKeyRotationStrategy(context: Context, strategy: ApiKeyRouter.Strategy) {
        context.agentDataStore.edit { it[KEY_API_KEY_ROTATION_STRATEGY] = strategy.name }
    }

    fun retryEnabled(context: Context): Flow<Boolean> =
        context.agentDataStore.data.map { prefs ->
            prefs[KEY_RETRY_ENABLED] ?: DEFAULT_RETRY_ENABLED
        }.recoverSettings("retryEnabled", DEFAULT_RETRY_ENABLED)

    suspend fun setRetryEnabled(context: Context, enabled: Boolean) {
        context.agentDataStore.edit { it[KEY_RETRY_ENABLED] = enabled }
    }

    fun retryAttempts(context: Context): Flow<Int> =
        context.agentDataStore.data.map { prefs ->
            (prefs[KEY_RETRY_ATTEMPTS] ?: DEFAULT_RETRY_ATTEMPTS).coerceIn(1, RetryPolicy.MAX_RETRY_ATTEMPTS)
        }.recoverSettings("retryAttempts", DEFAULT_RETRY_ATTEMPTS)

    suspend fun setRetryAttempts(context: Context, attempts: Int) {
        context.agentDataStore.edit {
            it[KEY_RETRY_ATTEMPTS] = attempts.coerceIn(1, RetryPolicy.MAX_RETRY_ATTEMPTS)
        }
    }

    suspend fun setPersona(context: Context, preset: String, instruction: String) {
        context.agentDataStore.edit { prefs ->
            prefs[KEY_PERSONA_PRESET] = preset
            if (instruction.isBlank()) prefs.remove(KEY_PERSONA_INSTRUCTION)
            else prefs[KEY_PERSONA_INSTRUCTION] = instruction.trim()
        }
    }

    // ---- Character cards (role personas with per-card phone-use capability) ----

    fun characterCards(context: Context): Flow<List<CharacterCard>> =
        context.agentDataStore.data.map { prefs ->
            CharacterCard.decode(prefs[KEY_CHARACTER_CARDS].orEmpty())
        }.recoverSettings("characterCards", emptyList())

    fun activeCharacterId(context: Context): Flow<String> =
        context.agentDataStore.data.map { prefs ->
            prefs[KEY_ACTIVE_CHARACTER_ID].orEmpty()
        }.recoverSettings("activeCharacterId", "")

    /** The currently selected card, or null when no card is active. */
    fun activeCharacter(context: Context): Flow<CharacterCard?> =
        context.agentDataStore.data.map { prefs ->
            val cards = CharacterCard.decode(prefs[KEY_CHARACTER_CARDS].orEmpty())
            val activeId = prefs[KEY_ACTIVE_CHARACTER_ID].orEmpty()
            cards.firstOrNull { it.id == activeId }
        }.recoverSettings("activeCharacter", null)

    /** Look up a single card by id, or null when not found. */
    fun characterCardById(context: Context, id: String?): Flow<CharacterCard?> =
        if (id.isNullOrBlank()) kotlinx.coroutines.flow.flowOf(null)
        else context.agentDataStore.data.map { prefs ->
            CharacterCard.decode(prefs[KEY_CHARACTER_CARDS].orEmpty()).firstOrNull { it.id == id }
        }.recoverSettings("characterCardById", null)

    suspend fun addCharacterCard(context: Context, card: CharacterCard) {
        context.agentDataStore.edit { prefs ->
            val cards = CharacterCard.decode(prefs[KEY_CHARACTER_CARDS].orEmpty()).toMutableList()
            cards.add(card)
            prefs[KEY_CHARACTER_CARDS] = CharacterCard.encode(cards)
        }
    }

    suspend fun updateCharacterCard(context: Context, card: CharacterCard) {
        context.agentDataStore.edit { prefs ->
            val cards = CharacterCard.decode(prefs[KEY_CHARACTER_CARDS].orEmpty()).toMutableList()
            val idx = cards.indexOfFirst { it.id == card.id }
            if (idx >= 0) cards[idx] = card else cards.add(card)
            prefs[KEY_CHARACTER_CARDS] = CharacterCard.encode(cards)
        }
    }

    suspend fun deleteCharacterCard(context: Context, id: String) {
        context.agentDataStore.edit { prefs ->
            val cards = CharacterCard.decode(prefs[KEY_CHARACTER_CARDS].orEmpty()).filterNot { it.id == id }
            if (cards.isEmpty()) prefs.remove(KEY_CHARACTER_CARDS) else prefs[KEY_CHARACTER_CARDS] = CharacterCard.encode(cards)
            if (prefs[KEY_ACTIVE_CHARACTER_ID] == id) prefs.remove(KEY_ACTIVE_CHARACTER_ID)
        }
    }

    /**
     * 首次启动时把内置角色卡写入存储。仅在角色卡存储为空时插入，避免覆盖用户
     * 已配置的内容。删除内置卡后**不会**自动恢复，避免把用户清掉的内容塞回来。
     *
     * 设计原则：
     * - 通用助手 / 写作 / 翻译 / 代码评审：仅对话，不授权手机工具，避免误操作。
     * - 手机管家：唯一默认开启 allowPhoneUse 的角色，方便用户上手体验；可随时在编辑页关闭。
     * - 文案贴近自然对话，避免硬性「你是 XX」开头影响后续多角色接力。
     */
    suspend fun ensureSeededCharacterCards(context: Context): Int {
        val existing = characterCards(context).first()
        if (existing.isNotEmpty()) return existing.size
        context.agentDataStore.edit { prefs ->
            prefs[KEY_CHARACTER_CARDS] = CharacterCard.encode(BUILTIN_CHARACTER_CARDS)
        }
        return BUILTIN_CHARACTER_CARDS.size
    }

    private val BUILTIN_CHARACTER_CARDS: List<CharacterCard> = listOf(
        CharacterCard(
            id = "builtin-assistant",
            name = "通用助手",
            description = """你是手机里的通用助手，名字叫「通用助手」。你的风格：

- 简洁、直接、不绕弯子。能用一个字说清的，不要用三个字。
- 默认使用简体中文回复；除非用户明确用其它语言提问。
- 不主动寒暄、不拍马屁。回答里不出现「当然！」「没问题！」这类填充词。
- 涉及代码、命令、文件路径时用代码块包裹。
- 不确定的事直说「我不确定」，并指出可以用哪些途径验证。
- 不会替你做主观决定（比如「周末去哪儿玩」），会列出 2-3 个选项让你挑。""",
            allowPhoneUse = false,
            source = "builtin"
        ),
        CharacterCard(
            id = "builtin-copywriter",
            name = "文案写作",
            description = """你是文案写作搭档，名字叫「文案写作」。你的工作方式：

- 接到写作任务时先问 1-2 个关键问题：用途（朋友圈/小红书/邮件/广告）、语气（正式/轻松/口语）、字数。
- 默认产出 3 个不同方向的版本（A 严谨、B 活泼、C 极简），不要只给一个。
- 长文案先给标题与小标题大纲，确认方向后再展开正文，避免一口气写完发现方向不对。
- 文风去 AI 味：禁用「赋能、闭环、抓手、深度参与」这类空转词；不用三段式排比；不要在结尾堆感叹号。
- 引用具体数字、模型名、版本号、用户场景；写不出数字就写机制。
- 修改时只动用户要求改的部分，不顺手「润色」其它段落。""",
            allowPhoneUse = false,
            source = "builtin"
        ),
        CharacterCard(
            id = "builtin-coder",
            name = "代码评审",
            description = """你是代码评审搭档，名字叫「代码评审」。你的评审风格：

- 默认回复简体中文，技术名词保留英文。
- 评审顺序：先指出严重问题（安全/正确性/数据丢失），再指出设计问题，最后是命名、格式这类细节。
- 每条意见给出具体的修改建议或反例代码，不要只说「这里不好」。
- 引用具体行号或函数名。不要写「感觉」、「可能」、「也许」这类模糊词。
- 区分「必须改」「建议改」「可改可不改」三档优先级。
- 不要给纯夸奖式评论（"写得很棒"），技术意见不积极也不消极，结论要可验证。
- 不会替你重写整个文件；只在评审里给出 diff 片段或局部示例。""",
            allowPhoneUse = false,
            source = "builtin"
        ),
        CharacterCard(
            id = "builtin-translator",
            name = "翻译助理",
            description = """你是翻译助理，名字叫「翻译助理」。你的工作方式：

- 默认源语言自动检测，目标语言跟随用户指示，未指定时询问。
- 翻译时优先保留原文的语气与语域（口语/正式/技术文档）；不要把所有句子都译成同一种腔调。
- 技术文档保留专业术语原文（首次出现给中文括号注释），后续直接用术语。
- 诗歌、广告文案、标题——这类保留格式和修辞的文本，译文后附一段「翻译说明」，解释为何这么处理。
- 不确定的术语或专有名词，宁可音译或保留原文也不臆造。
- 一次只翻译用户指定的内容，不要顺手「润色」没要求改的部分。""",
            allowPhoneUse = false,
            source = "builtin"
        ),
        CharacterCard(
            id = "builtin-phone",
            name = "手机管家",
            description = """你是手机端的设备操作助手，名字叫「手机管家」。你的工作方式：

- 用户让你「打开 XX」「查一下 YY」「设置里把 ZZ 打开」这类请求，先确认动作的预期影响，再调工具。
- 调用手机工具前先一句话告诉用户「接下来会做 X，可以吗」，得到确认后再发工具调用；不要一上来就执行多个工具。
- 涉及金钱（支付、充值）、删除（清空聊天、卸载应用）、发送（发消息、发邮件）这三类敏感动作，必须二次确认。
- 屏幕截图、点按、滑动这些操作，每次执行完主动告诉用户结果（打开了什么页面、看到了什么内容）。
- 不在结果里夹杂评价（"很棒"、"完美"），只描述事实。
- 当工具返回错误时，先判断是不是自己理解错了，再决定重试还是问用户。""",
            allowPhoneUse = true,
            source = "builtin"
        )
    )

    suspend fun setActiveCharacter(context: Context, id: String?) {
        context.agentDataStore.edit { prefs ->
            if (id.isNullOrBlank()) prefs.remove(KEY_ACTIVE_CHARACTER_ID)
            else prefs[KEY_ACTIVE_CHARACTER_ID] = id
        }
    }

    // ---- 侧边栏分区折叠状态（持久化） ----

    /** 已折叠的分区名集合（"角色会话" / "已固定会话" / "最近会话"）。 */
    fun collapsedSections(context: Context): Flow<Set<String>> =
        context.agentDataStore.data.map { prefs ->
            prefs[KEY_DRAWER_COLLAPSED]
                ?.split('\n')
                ?.map(String::trim)
                ?.filter(String::isNotBlank)
                ?.toSet()
                .orEmpty()
        }.recoverSettings("collapsedSections", emptySet())

    suspend fun setSectionCollapsed(context: Context, section: String, collapsed: Boolean) {
        context.agentDataStore.edit { prefs ->
            val current = prefs[KEY_DRAWER_COLLAPSED]
                ?.split('\n')
                ?.map(String::trim)
                ?.filter(String::isNotBlank)
                ?.toMutableSet()
                .orEmpty()
                .toMutableSet()
            if (collapsed) current.add(section) else current.remove(section)
            if (current.isEmpty()) prefs.remove(KEY_DRAWER_COLLAPSED)
            else prefs[KEY_DRAWER_COLLAPSED] = current.joinToString("\n")
        }
    }

    /** 后台任务通知标题（默认「Metis 后台任务」）。 */
    fun notificationTitle(context: Context): Flow<String> =
        context.agentDataStore.data.map { prefs -> prefs[KEY_NOTIF_TITLE].orEmpty() }
            .recoverSettings("notificationTitle", "")

    /** 后台任务进行中通知模板。支持 {step}、{last}、{max} 占位符。 */
    fun notificationRunningTemplate(context: Context): Flow<String> =
        context.agentDataStore.data.map { prefs -> prefs[KEY_NOTIF_RUNNING_TEMPLATE].orEmpty() }
            .recoverSettings("notificationRunningTemplate", "")

    /** 后台任务完成通知正文（默认「任务已完成」）。 */
    fun notificationCompleteText(context: Context): Flow<String> =
        context.agentDataStore.data.map { prefs -> prefs[KEY_NOTIF_COMPLETE_TEXT].orEmpty() }
            .recoverSettings("notificationCompleteText", "")

    suspend fun setNotificationTitle(context: Context, value: String) {
        context.agentDataStore.edit { prefs ->
            if (value.isBlank()) prefs.remove(KEY_NOTIF_TITLE) else prefs[KEY_NOTIF_TITLE] = value
        }
    }

    suspend fun setNotificationRunningTemplate(context: Context, value: String) {
        context.agentDataStore.edit { prefs ->
            if (value.isBlank()) prefs.remove(KEY_NOTIF_RUNNING_TEMPLATE) else prefs[KEY_NOTIF_RUNNING_TEMPLATE] = value
        }
    }

    suspend fun setNotificationCompleteText(context: Context, value: String) {
        context.agentDataStore.edit { prefs ->
            if (value.isBlank()) prefs.remove(KEY_NOTIF_COMPLETE_TEXT) else prefs[KEY_NOTIF_COMPLETE_TEXT] = value
        }
    }

    fun profileNickname(context: Context): Flow<String> = context.agentDataStore.data.map { prefs ->
        prefs[KEY_PROFILE_NICKNAME] ?: "Metis 用户"
    }.recoverSettings("profileNickname", "Metis 用户")

    fun profileEmail(context: Context): Flow<String> = context.agentDataStore.data.map { prefs ->
        prefs[KEY_PROFILE_EMAIL].orEmpty()
    }.recoverSettings("profileEmail", "")

    fun profileAvatarUri(context: Context): Flow<String> = context.agentDataStore.data.map { prefs ->
        prefs[KEY_PROFILE_AVATAR_URI].orEmpty()
    }.recoverSettings("profileAvatarUri", "")

    suspend fun setProfile(context: Context, nickname: String, email: String) {
        context.agentDataStore.edit { prefs ->
            prefs[KEY_PROFILE_NICKNAME] = nickname.trim().ifBlank { "Metis 用户" }
            prefs[KEY_PROFILE_EMAIL] = email.trim()
        }
    }

    suspend fun setProfileAvatarUri(context: Context, uri: String?) {
        context.agentDataStore.edit { prefs ->
            if (uri.isNullOrBlank()) prefs.remove(KEY_PROFILE_AVATAR_URI)
            else prefs[KEY_PROFILE_AVATAR_URI] = uri
        }
    }

    fun defaultProvider(context: Context): Flow<ModelProvider> =
        context.agentDataStore.data.map { prefs ->
            prefs[KEY_DEFAULT_PROVIDER]
                ?.let { runCatching { ModelProvider.valueOf(it) }.getOrNull() }
                ?.takeUnless { it == ModelProvider.CUSTOM_CLAUDE }
                ?: ModelProvider.CUSTOM_OPENAI
        }.recoverSettings("defaultProvider", ModelProvider.CUSTOM_OPENAI)

    suspend fun setDefaultProvider(context: Context, provider: ModelProvider) {
        context.agentDataStore.edit {
            it[KEY_DEFAULT_PROVIDER] = (if (provider == ModelProvider.CUSTOM_CLAUDE) ModelProvider.CUSTOM_OPENAI else provider).name
        }
    }

    fun maxSteps(context: Context): Flow<Int> =
        context.agentDataStore.data.map { prefs ->
            prefs[KEY_MAX_STEPS] ?: DEFAULT_MAX_STEPS
        }.recoverSettings("maxSteps", DEFAULT_MAX_STEPS)

    suspend fun setMaxSteps(context: Context, steps: Int) {
        context.agentDataStore.edit { it[KEY_MAX_STEPS] = steps }
    }

    fun mcpEnabled(context: Context): Flow<Boolean> =
        context.agentDataStore.data.map { prefs ->
            prefs[KEY_MCP_ENABLED] ?: false
        }.recoverSettings("mcpEnabled", false)

    suspend fun setMcpEnabled(context: Context, enabled: Boolean) {
        context.agentDataStore.edit { it[KEY_MCP_ENABLED] = enabled }
    }

    fun mcpPort(context: Context): Flow<Int> =
        context.agentDataStore.data.map { prefs ->
            prefs[KEY_MCP_PORT] ?: DEFAULT_MCP_PORT
        }.recoverSettings("mcpPort", DEFAULT_MCP_PORT)

    suspend fun setMcpPort(context: Context, port: Int) {
        context.agentDataStore.edit { it[KEY_MCP_PORT] = port }
    }

    fun maxContextTokens(context: Context): Flow<Int> =
        context.agentDataStore.data.map { prefs ->
            prefs[KEY_MAX_CONTEXT_TOKENS] ?: DEFAULT_MAX_CONTEXT_TOKENS
        }.recoverSettings("maxContextTokens", DEFAULT_MAX_CONTEXT_TOKENS)

    suspend fun setMaxContextTokens(context: Context, tokens: Int) {
        context.agentDataStore.edit { it[KEY_MAX_CONTEXT_TOKENS] = tokens }
    }

    fun compactionThreshold(context: Context): Flow<Int> =
        context.agentDataStore.data.map { prefs ->
            prefs[KEY_COMPACTION_THRESHOLD] ?: DEFAULT_COMPACTION_THRESHOLD
        }.recoverSettings("compactionThreshold", DEFAULT_COMPACTION_THRESHOLD)

    suspend fun setCompactionThreshold(context: Context, threshold: Int) {
        context.agentDataStore.edit { it[KEY_COMPACTION_THRESHOLD] = threshold }
    }

    suspend fun ensureMcpToken(context: Context): String {
        val prefs = context.agentDataStore.data.first()
        val existing = prefs[KEY_MCP_TOKEN]
        if (!existing.isNullOrBlank()) return existing

        val newToken = UUID.randomUUID().toString()
        context.agentDataStore.edit { it[KEY_MCP_TOKEN] = newToken }
        return newToken
    }

    fun mcpRelayUrl(context: Context): Flow<String> =
        context.agentDataStore.data.map { prefs ->
            prefs[KEY_MCP_RELAY_URL].orEmpty()
        }.recoverSettings("mcpRelayUrl", "")

    suspend fun setMcpRelay(context: Context, url: String, token: String) {
        context.agentDataStore.edit {
            it[KEY_MCP_RELAY_URL] = url.trim()
            it[KEY_MCP_RELAY_TOKEN] = token.trim()
        }
    }

    data class Snapshot(
        val gptApiKey: String,
        val deepseekApiKey: String,
        val baseUrl: String,
        val model: String,
        val defaultProvider: ModelProvider,
        val maxSteps: Int,
        val mcpEnabled: Boolean,
        val mcpPort: Int,
        val mcpToken: String,
        val mcpRelayUrl: String,
        val mcpRelayToken: String,
        val maxContextTokens: Int,
        val compactionThreshold: Int
        ,val personaPreset: String
        ,val personaInstruction: String
    )

    suspend fun snapshot(context: Context): Snapshot {
        val prefs = context.agentDataStore.data.first()
        // Remove credentials from the retired Claude provider when upgrading an old install.
        secrets(context).edit().remove("api_key").remove("anthropic_api_key").apply()
        return Snapshot(
            gptApiKey = gptApiKey(context),
            deepseekApiKey = deepseekApiKey(context),
            baseUrl = normalizeRelayBaseUrl(prefs[KEY_BASE_URL]),
            model = normalizeStoredModel(prefs[KEY_MODEL], prefs[KEY_DEFAULT_PROVIDER]),
            defaultProvider = prefs[KEY_DEFAULT_PROVIDER]
                ?.let { runCatching { ModelProvider.valueOf(it) }.getOrNull() }
                ?.takeUnless { it == ModelProvider.CUSTOM_CLAUDE }
                ?: ModelProvider.CUSTOM_OPENAI,
            maxSteps = prefs[KEY_MAX_STEPS] ?: DEFAULT_MAX_STEPS,
            mcpEnabled = prefs[KEY_MCP_ENABLED] ?: false,
            mcpPort = prefs[KEY_MCP_PORT] ?: DEFAULT_MCP_PORT,
            mcpToken = prefs[KEY_MCP_TOKEN] ?: "",
            mcpRelayUrl = prefs[KEY_MCP_RELAY_URL].orEmpty(),
            mcpRelayToken = prefs[KEY_MCP_RELAY_TOKEN].orEmpty(),
            maxContextTokens = prefs[KEY_MAX_CONTEXT_TOKENS] ?: DEFAULT_MAX_CONTEXT_TOKENS,
            compactionThreshold = prefs[KEY_COMPACTION_THRESHOLD] ?: DEFAULT_COMPACTION_THRESHOLD
            ,personaPreset = prefs[KEY_PERSONA_PRESET] ?: PERSONA_PRESET_FRIENDLY
            ,personaInstruction = prefs[KEY_PERSONA_INSTRUCTION].orEmpty()
        )
    }

    fun Snapshot.keyFor(provider: ModelProvider): String = when (provider) {
        // Legacy Claude sessions are migrated onto the GPT relay without exposing Claude settings.
        ModelProvider.CUSTOM_CLAUDE -> gptApiKey
        ModelProvider.CUSTOM_OPENAI -> gptApiKey
        ModelProvider.DEEPSEEK -> deepseekApiKey
    }

    fun Snapshot.baseUrlFor(provider: ModelProvider): String = when (provider) {
        ModelProvider.CUSTOM_CLAUDE, ModelProvider.CUSTOM_OPENAI ->
            normalizeRelayBaseUrl(baseUrl)
        ModelProvider.DEEPSEEK -> DEEPSEEK_BASE_URL
    }

    fun Snapshot.defaultModelFor(provider: ModelProvider): String =
        normalizeModel(provider, model).takeIf { provider == defaultProvider && it.isNotBlank() }
            ?: ModelCatalog.defaultFor(provider).let { fallback ->
                if (provider == ModelProvider.CUSTOM_CLAUDE) ModelCatalog.defaultFor(ModelProvider.CUSTOM_OPENAI) else fallback
            }

    private fun normalizeRelayBaseUrl(value: String?): String =
        value?.trim()?.takeUnless {
            it.isBlank() ||
                it == LEGACY_DEFAULT_BASE_URL ||
                // 旧的私有中转站默认值：清空强制用户重新填写。
                it == "https://api.pinaic.com" ||
                it == "http://api.pinaic.com"
        }
            ?: DEFAULT_RELAY_BASE_URL

    private fun normalizeStoredModel(value: String?, providerName: String?): String {
        val provider = providerName?.let { runCatching { ModelProvider.valueOf(it) }.getOrNull() }
        return normalizeModel(provider, value.orEmpty())
    }

    private fun normalizeModel(provider: ModelProvider?, value: String): String {
        if (value.startsWith("claude", ignoreCase = true)) return DEFAULT_MODEL
        if (value.equals("deepseek-chat", ignoreCase = true) || value.startsWith("deepseek-v3", ignoreCase = true)) {
            return ModelCatalog.defaultFor(ModelProvider.DEEPSEEK)
        }
        return value.takeIf { it.isNotBlank() } ?: when (provider) {
            ModelProvider.DEEPSEEK -> ModelCatalog.defaultFor(ModelProvider.DEEPSEEK)
            else -> DEFAULT_MODEL
        }
    }
}

private fun <T> Flow<T>.recoverSettings(label: String, fallback: T): Flow<T> =
    catch { error ->
        EventLog.append("settings> $label read failed: ${error.message}")
        emit(fallback)
    }
