package com.mrgreenapps.a11ypilot.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mrgreenapps.a11ypilot.ServiceState
import com.mrgreenapps.a11ypilot.agent.AgentSettings
import com.mrgreenapps.a11ypilot.agent.GitHubReleaseChecker
import com.mrgreenapps.a11ypilot.BuildConfig
import com.mrgreenapps.a11ypilot.agent.CharacterCard
import com.mrgreenapps.a11ypilot.agent.CharacterCardParser
import com.mrgreenapps.a11ypilot.agent.ToolExecutor
import com.mrgreenapps.a11ypilot.agent.NetworkDiagnosticResult
import com.mrgreenapps.a11ypilot.agent.NetworkDiagnostics
import com.mrgreenapps.a11ypilot.agent.DeepSeekModelProbe
import com.mrgreenapps.a11ypilot.agent.OpenAIModelProbe
import com.mrgreenapps.a11ypilot.data.*
import com.mrgreenapps.a11ypilot.utils.ResponsiveLayout
import com.mrgreenapps.a11ypilot.tools.TermuxCommandRunner
import com.mrgreenapps.a11ypilot.tools.DocumentTool
import com.mrgreenapps.a11ypilot.phoneuse.PhoneUseService
import com.mrgreenapps.a11ypilot.ui.theme.FiraCodeFamily
import com.mrgreenapps.a11ypilot.ui.components.CharacterAvatarPicker
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage
import kotlin.math.roundToInt

private enum class SettingsPage(val title: String) {
    MODEL("默认模型与推理"),
    API_KEYS("API 密钥管理"),
    USAGE("用量统计"),
    SAFETY("安全策略"),
    APPEARANCE("外观与字体"),
    PHONE_USE("PhoneUse"),
    FILES("文件访问"),
    TOOLS("Termux 与 MCP"),
    NETWORK("网络诊断"),
    CUSTOM_METIS("自定义 Metis"),
    CHARACTER_CARDS("角色卡"),
    NOTIFICATION("后台通知")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(bottomBarInset: Dp = 0.dp, onExit: (() -> Unit)? = null) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val sessionRepository = remember { SessionRepository(context) }
    var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var releaseDialog by remember { mutableStateOf<com.mrgreenapps.a11ypilot.agent.GitHubRelease?>(null) }
    var releaseChecking by remember { mutableStateOf(false) }

    val phoneUseEnabled by ServiceState.enabled.collectAsState()
    val phoneUseRestricted by ServiceState.restricted.collectAsState()
    val savedBaseUrl by AgentSettings.baseUrl(context).collectAsState(initial = AgentSettings.DEFAULT_RELAY_BASE_URL)
    val savedModel by AgentSettings.model(context).collectAsState(initial = AgentSettings.DEFAULT_MODEL)
    val savedProvider by AgentSettings.defaultProvider(context).collectAsState(initial = ModelProvider.CUSTOM_OPENAI)
    val savedMaxSteps by AgentSettings.maxSteps(context).collectAsState(initial = AgentSettings.DEFAULT_MAX_STEPS)
    val savedMcpEnabled by AgentSettings.mcpEnabled(context).collectAsState(initial = false)
    val savedMcpPort by AgentSettings.mcpPort(context).collectAsState(initial = AgentSettings.DEFAULT_MCP_PORT)
    val savedThemeStyle by ThemeSettings.getThemeStyle(context).collectAsState(initial = AppThemeStyle.CLAUDE)
    val savedThemeMode by ThemeSettings.getThemeMode(context).collectAsState(initial = AppThemeMode.SYSTEM)
    val savedFontScale by ThemeSettings.getFontScale(context).collectAsState(initial = 1f)
    val savedSafetyLevel by SafetySettings.getSafetyLevel(context).collectAsState(initial = SafetyLevel.BALANCED)
    val savedPersonaPreset by AgentSettings.personaPreset(context).collectAsState(initial = AgentSettings.PERSONA_PRESET_FRIENDLY)
    val savedPersonaInstruction by AgentSettings.personaInstruction(context).collectAsState(initial = "")
    val termuxCapability = TermuxCommandRunner(context).diagnoseExternal()

    var gptKey by remember { mutableStateOf(AgentSettings.gptApiKey(context)) }
    var deepSeekKey by remember { mutableStateOf(AgentSettings.deepseekApiKey(context)) }
    var baseUrl by remember(savedBaseUrl) { mutableStateOf(savedBaseUrl) }
    var provider by remember(savedProvider) { mutableStateOf(savedProvider) }
    var model by remember(savedModel) { mutableStateOf(savedModel) }
    var maxSteps by remember(savedMaxSteps) { mutableStateOf(savedMaxSteps.toString()) }
    var mcpEnabled by remember(savedMcpEnabled) { mutableStateOf(savedMcpEnabled) }
    var mcpPort by remember(savedMcpPort) { mutableStateOf(savedMcpPort.toString()) }
    var showGptKey by remember { mutableStateOf(false) }
    var showDeepSeekKey by remember { mutableStateOf(false) }
    var deepSeekModels by remember { mutableStateOf<List<String>?>(null) }
    var probingDeepSeek by remember { mutableStateOf(false) }
    var gptModels by remember { mutableStateOf<List<String>?>(null) }
    var probingGpt by remember { mutableStateOf(false) }
    var personaPreset by remember(savedPersonaPreset) { mutableStateOf(savedPersonaPreset) }
    var personaInstruction by remember(savedPersonaInstruction) { mutableStateOf(savedPersonaInstruction) }
    var workspaceUri by remember { mutableStateOf(DocumentTool.workspaceTreeUri(context)) }
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val persisted = DocumentTool.setWorkspaceTree(context, uri)
            workspaceUri = uri.takeIf { persisted }
            notice = if (persisted) {
                "外部文件夹已授权，智能体可通过 workspace/ 读取"
            } else {
                "系统未提供可持久化的文件夹授权，请换一个文件管理器"
            }
        }
    }

    // Character card import: pick a PNG or JSON SillyTavern card file.
    val characterCards by AgentSettings.characterCards(context).collectAsState(initial = emptyList())
    val activeCharacterId by AgentSettings.activeCharacterId(context).collectAsState(initial = "")
    val characterCardPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val bytes = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull()
                when (val result = if (bytes == null) {
                    CharacterCardParser.Result.Error("无法读取所选文件")
                } else {
                    CharacterCardParser.fromBytes(bytes)
                }) {
                    is CharacterCardParser.Result.Success -> {
                        AgentSettings.addCharacterCard(context, result.card)
                        notice = "角色卡「${result.card.name}」已导入"
                    }
                    is CharacterCardParser.Result.Error -> notice = result.message
                }
            }
        }
    }

    val persistedDeepSeekModels by AgentSettings.deepseekModels(context).collectAsState(initial = emptyList())
    val persistedGptModels by AgentSettings.gptModels(context).collectAsState(initial = emptyList())

    fun saveModelSettings() {
        scope.launch {
            runCatching {
                AgentSettings.setGptApiKey(context, gptKey.trim())
                AgentSettings.setDeepseekApiKey(context, deepSeekKey.trim())
                AgentSettings.setBaseUrl(context, baseUrl.trim().ifEmpty { AgentSettings.DEFAULT_RELAY_BASE_URL })
                AgentSettings.setDefaultProvider(context, provider)
                val selectedModel = model.ifBlank { ModelCatalog.defaultFor(provider) }
                AgentSettings.setModel(context, selectedModel)
                sessionRepository.syncEmptyDefaultSessions(provider, selectedModel)
                AgentSettings.setMaxSteps(context, maxSteps.toIntOrNull()?.coerceIn(1, 100) ?: AgentSettings.DEFAULT_MAX_STEPS)
            }.onSuccess { notice = "模型与 API 设置已保存" }
                .onFailure { notice = "保存失败：${it.message}" }
        }
    }

    fun saveToolSettings() {
        scope.launch {
            AgentSettings.setMcpEnabled(context, mcpEnabled)
            AgentSettings.setMcpPort(context, mcpPort.toIntOrNull()?.coerceIn(1024, 65535) ?: AgentSettings.DEFAULT_MCP_PORT)
            notice = "工具设置已保存"
        }
    }

    fun savePersonaSettings() {
        scope.launch {
            AgentSettings.setPersona(context, personaPreset, personaInstruction)
            notice = "Metis 回复风格已保存"
        }
    }

    fun probeDeepSeekModels() {
        scope.launch {
            probingDeepSeek = true
            deepSeekModels = runCatching {
                DeepSeekModelProbe.probe(deepSeekKey.trim())
            }.getOrElse { listOf("探测失败：${it.message ?: "未知错误"}") }
            deepSeekModels
                ?.filterNot { it.startsWith("探测失败") }
                ?.takeIf { it.isNotEmpty() }
                ?.let { AgentSettings.setDeepseekModels(context, it) }
            probingDeepSeek = false
        }
    }

    fun probeGptModels() {
        scope.launch {
            probingGpt = true
            gptModels = runCatching { OpenAIModelProbe.probe(baseUrl, gptKey.trim()) }
                .getOrElse { listOf("探测失败：${it.message ?: "未知错误"}") }
            gptModels?.filterNot { it.startsWith("探测失败") }?.takeIf { it.isNotEmpty() }?.let {
                AgentSettings.setGptModels(context, it)
            }
            probingGpt = false
        }
    }

    BackHandler(enabled = page != null || onExit != null) {
        if (page != null) page = null else onExit?.invoke()
    }
    LaunchedEffect(provider, persistedDeepSeekModels) {
        val knownModels = if (provider == ModelProvider.DEEPSEEK) {
            ModelCatalog.normalizeDeepSeekModels(persistedDeepSeekModels)
        } else {
            ModelCatalog.forProvider(provider)
        }
        if (model !in knownModels ||
            (provider == ModelProvider.DEEPSEEK &&
                (model.equals("deepseek-chat", ignoreCase = true) || model.startsWith("deepseek-v3", ignoreCase = true)))) {
            model = ModelCatalog.defaultFor(provider)
        }
    }
    LaunchedEffect(Unit) { ServiceState.refresh(context) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) ServiceState.refresh(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(notice) {
        if (notice != null) {
            kotlinx.coroutines.delay(2500)
            notice = null
        }
    }

    releaseDialog?.let { release ->
        AlertDialog(
            onDismissRequest = { releaseDialog = null },
            title = { Text("发现新版本 ${release.tagName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("当前版本：v${BuildConfig.VERSION_NAME}")
                    Text("最新版本：${release.tagName}")
                    Text(release.body.ifBlank { "新版本已发布，建议前往 GitHub 查看更新说明。" }, maxLines = 8)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    releaseDialog = null
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.htmlUrl)))
                }) { Text("前往下载") }
            },
            dismissButton = { TextButton(onClick = { releaseDialog = null }) { Text("暂不更新") } }
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(page?.title ?: "Metis 设置")
                    }
                },
                navigationIcon = {
                    if (page != null) {
                        IconButton(onClick = { page = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回设置")
                        }
                    } else if (onExit != null) {
                        IconButton(onClick = onExit) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回 Metis")
                        }
                    }
                }
            )
        },
        snackbarHost = { notice?.let { Snackbar(Modifier.padding(12.dp)) { Text(it) } } }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(bottom = bottomBarInset)
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .widthIn(max = 760.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = ResponsiveLayout.responsiveHorizontalPadding(), vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (page == null) {
                    SettingsProfileHeader()
                }
                when (page) {
                    null -> SettingsHome(onOpen = { page = it }, checkingUpdate = releaseChecking, onCheckUpdate = {
                        scope.launch {
                            releaseChecking = true
                            GitHubReleaseChecker.checkLatest().onSuccess { release ->
                                if (GitHubReleaseChecker.isNewer(release.tagName)) {
                                    releaseDialog = release
                                } else {
                                    notice = "当前已是最新版本 v${BuildConfig.VERSION_NAME}，暂时没有可用更新。"
                                }
                            }.onFailure {
                                notice = "检查更新失败：${it.message ?: "请检查网络连接后重试"}"
                            }
                            releaseChecking = false
                        }
                    })
                    SettingsPage.MODEL -> ModelApiSettings(
                        gptKey, { gptKey = it }, showGptKey, { showGptKey = !showGptKey },
                        deepSeekKey, { deepSeekKey = it }, showDeepSeekKey, { showDeepSeekKey = !showDeepSeekKey },
                         (deepSeekModels ?: persistedDeepSeekModels).takeIf { it.isNotEmpty() }, probingDeepSeek, ::probeDeepSeekModels,
                         (gptModels ?: persistedGptModels).takeIf { it.isNotEmpty() }, probingGpt, ::probeGptModels,
                        baseUrl, { baseUrl = it }, provider, { provider = it }, model, { model = it },
                         maxSteps, { maxSteps = it.filter(Char::isDigit) }, ::saveModelSettings,
                         onOpenApiKeys = { page = SettingsPage.API_KEYS }
                     )
                    SettingsPage.API_KEYS -> ApiKeyManagementScreen(onNotice = { notice = it })
                    SettingsPage.USAGE -> UsageStatsScreen(onRefresh = { notice = "统计数据会随模型任务自动更新" })
                    SettingsPage.SAFETY -> SafetySettingsPage(savedSafetyLevel) { level ->
                        scope.launch { SafetySettings.setSafetyLevel(context, level) }
                    }
                    SettingsPage.APPEARANCE -> AppearanceSettingsPage(
                        savedThemeStyle, { scope.launch { ThemeSettings.setThemeStyle(context, it) } },
                        savedThemeMode, { scope.launch { ThemeSettings.setThemeMode(context, it) } },
                        savedFontScale, { scope.launch { ThemeSettings.setFontScale(context, it) } }
                    )
                    SettingsPage.PHONE_USE -> PhoneUseSettingsPage(
                        phoneUseEnabled,
                        phoneUseRestricted,
                        { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                        context
                    )
                    SettingsPage.FILES -> FileAccessSettingsPage(
                        authorized = workspaceUri != null,
                        onChoose = { folderPicker.launch(null) },
                        onClear = {
                            DocumentTool.clearWorkspaceTree(context)
                            workspaceUri = null
                            notice = "外部文件夹授权已清除"
                        }
                    )
                    SettingsPage.TOOLS -> ToolSettingsPage(
                        mcpEnabled, { mcpEnabled = it }, mcpPort,
                        { mcpPort = it.filter(Char::isDigit) }, termuxCapability, ::saveToolSettings
                    )
                    SettingsPage.NETWORK -> NetworkSettingsPage(baseUrl, gptKey)
                    SettingsPage.CUSTOM_METIS -> CustomMetisSettingsPage(
                        preset = personaPreset,
                        onPreset = { personaPreset = it },
                        instruction = personaInstruction,
                        onInstruction = { personaInstruction = it },
                        onSave = ::savePersonaSettings
                    )
                    SettingsPage.CHARACTER_CARDS -> CharacterCardsPage(
                        cards = characterCards,
                        activeId = activeCharacterId,
                        onImport = { characterCardPicker.launch("*/*") },
                        onSelect = { scope.launch { AgentSettings.setActiveCharacter(context, it) } },
                        onSave = { card -> scope.launch { AgentSettings.updateCharacterCard(context, card) } },
                        onDelete = { id -> scope.launch { AgentSettings.deleteCharacterCard(context, id) } },
                        onNotice = { notice = it }
                    )
                    SettingsPage.NOTIFICATION -> NotificationSettingsPage()
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun SettingsHome(onOpen: (SettingsPage) -> Unit, checkingUpdate: Boolean, onCheckUpdate: () -> Unit) {
    SettingsGroup("模型配置") {
        SettingsEntry(Icons.Default.SmartToy, "默认模型与推理") { onOpen(SettingsPage.MODEL) }
        SettingsEntry(Icons.Default.Key, "API 密钥管理") { onOpen(SettingsPage.API_KEYS) }
        SettingsEntry(Icons.Default.Analytics, "用量统计") { onOpen(SettingsPage.USAGE) }
        SettingsEntry(Icons.Default.Security, "安全策略") { onOpen(SettingsPage.SAFETY) }
    }
    SettingsGroup("个性化") {
        SettingsEntry(Icons.Default.Face, "角色卡") { onOpen(SettingsPage.CHARACTER_CARDS) }
        SettingsEntry(Icons.Default.RecordVoiceOver, "系统提示词") { onOpen(SettingsPage.CUSTOM_METIS) }
        SettingsEntry(Icons.Default.Palette, "主题外观与字体") { onOpen(SettingsPage.APPEARANCE) }
    }
    SettingsGroup("权限与能力") {
        SettingsEntry(Icons.Default.Smartphone, "无障碍与 PhoneUse") { onOpen(SettingsPage.PHONE_USE) }
        SettingsEntry(Icons.Default.FolderOpen, "文件访问") { onOpen(SettingsPage.FILES) }
        SettingsEntry(Icons.Default.Terminal, "Termux 与 MCP") { onOpen(SettingsPage.TOOLS) }
    }
    SettingsGroup("高级") {
        SettingsEntry(Icons.Default.Wifi, "网络设置与诊断") { onOpen(SettingsPage.NETWORK) }
        SettingsEntry(Icons.Default.Notifications, "后台通知") { onOpen(SettingsPage.NOTIFICATION) }
    }
    SettingsGroup("关于") {
        SettingsEntry(Icons.Default.Info, "Metis Mobile v${BuildConfig.VERSION_NAME}", enabled = false) {}
        SettingsEntry(Icons.Default.Description, "开源协议：Apache 2.0", enabled = false) {}
        SettingsEntry(Icons.Default.Code, "GitHub：linyeping/metis-mobile", enabled = false) {}
        SettingsEntry(Icons.Default.SystemUpdate, if (checkingUpdate) "正在检查 GitHub 更新…" else "检查更新", enabled = !checkingUpdate) { onCheckUpdate() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsProfileHeader() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val nickname by AgentSettings.profileNickname(context).collectAsState(initial = "Metis 用户")
    val email by AgentSettings.profileEmail(context).collectAsState(initial = "")
    val avatarUri by AgentSettings.profileAvatarUri(context).collectAsState(initial = "")
    var showEditor by remember { mutableStateOf(false) }
    var draftNickname by remember { mutableStateOf(nickname) }
    var draftEmail by remember { mutableStateOf(email) }
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            scope.launch { AgentSettings.setProfileAvatarUri(context, uri.toString()) }
        }
    }
    LaunchedEffect(showEditor, nickname, email) {
        if (showEditor) {
            draftNickname = nickname
            draftEmail = email
        }
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(Modifier.size(82.dp), contentAlignment = Alignment.BottomEnd) {
            Surface(
                modifier = Modifier
                    .size(72.dp)
                    .align(Alignment.TopStart)
                    .clickable {
                        draftNickname = nickname
                        draftEmail = email
                        showEditor = true
                    },
                shape = androidx.compose.foundation.shape.CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                if (avatarUri.isNotBlank()) {
                    AsyncImage(
                        model = Uri.parse(avatarUri),
                        contentDescription = "头像",
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Person, "头像", modifier = Modifier.size(38.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Surface(
                modifier = Modifier.size(28.dp).clickable {
                    draftNickname = nickname
                    draftEmail = email
                    showEditor = true
                },
                shape = androidx.compose.foundation.shape.CircleShape,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                shadowElevation = 2.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Edit, "编辑个人资料", modifier = Modifier.size(16.dp))
                }
            }
        }
    }

    if (showEditor) {
        ModalBottomSheet(onDismissRequest = { showEditor = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("个人资料", style = MaterialTheme.typography.titleLarge)
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Surface(
                        modifier = Modifier.size(88.dp),
                        shape = androidx.compose.foundation.shape.CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        if (avatarUri.isNotBlank()) {
                            AsyncImage(Uri.parse(avatarUri), "头像", Modifier.fillMaxSize())
                        } else {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Person, "头像", Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    FilledTonalIconButton(
                        onClick = { avatarPicker.launch(arrayOf("image/*")) },
                        modifier = Modifier.align(Alignment.BottomCenter).offset(x = 32.dp, y = 8.dp)
                    ) { Icon(Icons.Default.PhotoCamera, "从相册选择头像") }
                }
                OutlinedTextField(
                    value = draftNickname,
                    onValueChange = { draftNickname = it.take(24) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("昵称") }
                )
                OutlinedTextField(
                    value = draftEmail,
                    onValueChange = { draftEmail = it.take(80) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("邮箱") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showEditor = false }, modifier = Modifier.weight(1f)) { Text("取消") }
                    Button(
                        onClick = {
                            scope.launch { AgentSettings.setProfile(context, draftNickname, draftEmail) }
                            showEditor = false
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("保存") }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 3.dp)
        )
        content()
        HorizontalDivider(Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun SettingsEntry(icon: ImageVector, title: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 50.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        if (enabled) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
    }
}

@Composable
private fun ModelApiSettings(
    gptKey: String, onGptKey: (String) -> Unit, showGptKey: Boolean, toggleGpt: () -> Unit,
    deepSeekKey: String, onDeepSeekKey: (String) -> Unit, showDeepSeekKey: Boolean, toggleDeepSeek: () -> Unit,
    deepSeekModels: List<String>?, probingDeepSeek: Boolean, onProbeDeepSeek: () -> Unit,
    gptModels: List<String>?, probingGpt: Boolean, onProbeGpt: () -> Unit,
    baseUrl: String, onBaseUrl: (String) -> Unit,
    provider: ModelProvider, onProvider: (ModelProvider) -> Unit,
    model: String, onModel: (String) -> Unit,
    maxSteps: String, onMaxSteps: (String) -> Unit,
    onSave: () -> Unit,
    onOpenApiKeys: () -> Unit
) {
    SettingsSection("API 密钥", "密钥档案、BaseURL、探针和备注集中管理") {
        OutlinedButton(onClick = onOpenApiKeys, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Key, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("打开 API 密钥管理")
        }
    }
    SettingsSection("新会话默认值", "每个会话可继续选择不同模型和推理强度") {
        ModelProvider.entries.filter { it != ModelProvider.CUSTOM_CLAUDE }.forEach { item ->
            RadioSetting(item.displayName, provider == item) { onProvider(item) }
        }
        ModelSelector(provider, model, onModel, deepSeekModels, gptModels)
        OutlinedTextField(
            maxSteps, onMaxSteps, label = { Text("单次任务最大步骤") }, modifier = Modifier.fillMaxWidth(),
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
    }
    Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Save, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("保存")
    }
}

@Composable
private fun SafetySettingsPage(selected: SafetyLevel, onSelected: (SafetyLevel) -> Unit) {
    SettingsSection("默认安全策略", "新会话继承该策略，已有会话不受影响") {
        SafetyLevel.entries.forEach { level ->
            RadioSetting(level.displayName, selected == level, level.description) { onSelected(level) }
            // 展开当前选中策略的具体权限明细
            if (selected == level) {
                Column(
                    modifier = Modifier.padding(start = 40.dp, top = 4.dp, bottom = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    SafetySettings.getConfigForLevel(level).detailLines().forEach { line ->
                        Text(
                            "• $line",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationSettingsPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val title by AgentSettings.notificationTitle(context).collectAsState(initial = "")
    val completeText by AgentSettings.notificationCompleteText(context).collectAsState(initial = "")
    val runningTemplate by AgentSettings.notificationRunningTemplate(context).collectAsState(initial = "")
    var draftTitle by remember { mutableStateOf(title) }
    var draftComplete by remember { mutableStateOf(completeText) }
    var draftTemplate by remember { mutableStateOf(runningTemplate) }
    LaunchedEffect(title, completeText, runningTemplate) {
        draftTitle = title
        draftComplete = completeText
        draftTemplate = runningTemplate
    }
    SettingsSection("后台通知内容", "任务在后台运行时，通知栏显示这些文案；留空则使用默认") {
        OutlinedTextField(
            value = draftTitle,
            onValueChange = { draftTitle = it },
            label = { Text("通知标题") },
            placeholder = { Text("Metis 后台任务") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = draftTemplate,
            onValueChange = { draftTemplate = it },
            label = { Text("运行中通知模板") },
            placeholder = { Text("{last} · 第 {step} 步") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("可用占位符：{last} 当前动作、{step} 当前步、{max} 总步数") }
        )
        OutlinedTextField(
            value = draftComplete,
            onValueChange = { draftComplete = it },
            label = { Text("完成通知正文") },
            placeholder = { Text("任务已完成") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                scope.launch {
                    AgentSettings.setNotificationTitle(context, draftTitle.trim())
                    AgentSettings.setNotificationRunningTemplate(context, draftTemplate.trim())
                    AgentSettings.setNotificationCompleteText(context, draftComplete.trim())
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("保存") }
        Text(
            "运行中的通知会自动带上进度，无需额外设置。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AppearanceSettingsPage(
    style: AppThemeStyle, onStyle: (AppThemeStyle) -> Unit,
    mode: AppThemeMode, onMode: (AppThemeMode) -> Unit,
    fontScale: Float, onFontScale: (Float) -> Unit
) {
    SettingsSection("配色", "选择后立即生效") {
        AppThemeStyle.entries.forEach {
            RadioSetting("Claude", style == it) { onStyle(it) }
        }
    }
    SettingsSection("明暗模式", "可固定浅色、深色或跟随系统") {
        AppThemeMode.entries.forEach { RadioSetting(it.displayName, mode == it) { onMode(it) } }
    }
    SettingsSection("字体大小", "") {
        val percent = (fontScale * 100f).toInt()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("UI 字号", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text("$percent%", style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            value = fontScale,
            onValueChange = onFontScale,
            valueRange = 0.8f..1.4f,
            steps = 11,
            modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("小", style = MaterialTheme.typography.labelSmall)
            Text("大", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun PhoneUseSettingsPage(
    phoneUseEnabled: Boolean,
    phoneUseRestricted: Boolean,
    onOpenAccessibility: () -> Unit,
    context: android.content.Context
) {
    val scope = rememberCoroutineScope()
    var diagnostic by remember { mutableStateOf<String?>(null) }
    SettingsSection("手机操作服务", "Metis 通过无障碍服务读取并操作其他应用") {
        val service = PhoneUseService.getInstance()
        StatusRow(
            "PhoneUse",
            when {
                phoneUseRestricted -> "系统显示已启用，但服务实例未连接（可能被系统限制）"
                phoneUseEnabled -> service?.stateDescription() ?: "已连接，等待活动窗口"
                else -> "需要在系统设置中启用"
            },
            phoneUseEnabled && !phoneUseRestricted
        )
        if (phoneUseRestricted) {
            Text(
                "检测到无障碍服务处于“已启用但未连接”状态。请在系统无障碍设置中重新关闭并开启 Metis；部分国产 ROM 还需要在应用信息中允许受限制的设置。",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth()
            )
        }
        OutlinedButton(onClick = onOpenAccessibility, modifier = Modifier.fillMaxWidth()) {
            Text(if (phoneUseEnabled) "查看无障碍设置" else "启用 PhoneUse")
        }
        OutlinedButton(
            enabled = phoneUseEnabled && !phoneUseRestricted,
            onClick = {
                scope.launch {
                    diagnostic = when (val result = ToolExecutor(context).dumpScreen()) {
                        is ToolExecutor.Result.Ok -> result.screen.lineSequence().take(8).joinToString("\n")
                        is ToolExecutor.Result.Err -> "读取失败：${result.message}"
                        is ToolExecutor.Result.Done -> result.summary
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("测试读取当前屏幕") }
        diagnostic?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun FileAccessSettingsPage(
    authorized: Boolean,
    onChoose: () -> Unit,
    onClear: () -> Unit
) {
    SettingsSection("外部文件工作区", "通过系统文件夹授权读取手机上的项目、文档和代码，不依赖 Termux") {
        StatusRow(
            "workspace/",
            if (authorized) "已授权，可递归读取" else "尚未授权",
            authorized
        )
        Button(onClick = onChoose, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (authorized) "更换文件夹" else "选择文件夹")
        }
        if (authorized) {
            OutlinedButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) {
                Text("清除文件夹授权")
            }
        }
        Text(
            "读取路径使用 workspace/文件名，例如 workspace/项目/src/Main.kt。外部工作区暂为只读，生成文件仍写入 Metis 文档目录。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ToolSettingsPage(
    enabled: Boolean, onEnabled: (Boolean) -> Unit,
    port: String, onPort: (String) -> Unit,
    termuxCapability: TermuxCommandRunner.Capability,
    onSave: () -> Unit
) {
    val context = LocalContext.current
    val setupCommand = TermuxCommandRunner.EXTERNAL_ACCESS_SETUP_COMMAND

    SettingsSection("内置命令器", "未连接完整 Termux 时，Metis 会在自己的私有工作目录运行短命令") {
        StatusRow(
            "Metis shell",
            "已内置，可运行基础 shell、文件检查和短脚本；不提供 apt、Node/Python 或完整 Linux 用户空间",
            true
        )
    }
    SettingsSection("Termux 命令桥接", "Code 模式通过 Termux RunCommandService 执行命令") {
        StatusRow(
            "Termux",
            termuxCapability.detail,
            termuxCapability == TermuxCommandRunner.Capability.READY
        )
        if (termuxCapability == TermuxCommandRunner.Capability.READY) {
            Text(
                "首次使用仍需在 Termux 开启外部应用调用：",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    setupCommand,
                    modifier = Modifier.padding(10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FiraCodeFamily
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val launchIntent = context.packageManager.getLaunchIntentForPackage("com.termux")
                        if (launchIntent != null) context.startActivity(launchIntent)
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("打开 Termux") }
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Termux 配置命令", setupCommand))
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("复制命令") }
            }
        }
    }
    SettingsSection("MCP 服务", "供同一网络中的 MCP 客户端调用 Metis 工具") {
        SettingSwitch("启用 MCP 服务", enabled, onEnabled)
        if (enabled) {
            OutlinedTextField(
                port, onPort, label = { Text("监听端口") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }
    }
    Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) { Text("保存工具设置") }
}

@Composable
private fun NetworkSettingsPage(baseUrl: String, apiKey: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val retryEnabled by AgentSettings.retryEnabled(context).collectAsState(initial = AgentSettings.DEFAULT_RETRY_ENABLED)
    val retryAttempts by AgentSettings.retryAttempts(context).collectAsState(initial = AgentSettings.DEFAULT_RETRY_ATTEMPTS)
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<NetworkDiagnosticResult?>(null) }
    var retryAttemptsDraft by remember(retryAttempts) { mutableFloatStateOf(retryAttempts.toFloat()) }

    fun runDiagnostic() {
        scope.launch {
            running = true
            result = NetworkDiagnostics.inspect(context, baseUrl, apiKey)
            running = false
        }
    }
    LaunchedEffect(Unit) { runDiagnostic() }

    SettingsSection("当前网络路径", "这里显示 Metis 进程实际看到的网络状态") {
        DiagnosticRow("活动传输", result?.transport ?: "检测中")
        DiagnosticRow("VPN transport", when (result?.vpnActive) { true -> "已检测到"; false -> "未检测到"; null -> "检测中" })
        DiagnosticRow("系统代理", result?.systemProxy ?: "检测中")
        DiagnosticRow("Base URL", result?.baseUrl ?: baseUrl)
        DiagnosticRow("网络验证", when (result?.validated) { true -> "系统已验证互联网"; false -> "系统未验证互联网"; null -> "检测中" })
        DiagnosticRow("DNS", result?.dnsServers ?: "检测中")
        DiagnosticRow("全部网络", result?.networkSummary ?: "检测中")
        DiagnosticRow("连通性", result?.connectivity ?: "检测中")
        FilledTonalButton(onClick = ::runDiagnostic, enabled = !running, modifier = Modifier.fillMaxWidth()) {
            if (running) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("重新检测")
        }
    }
    SettingsSection("任务自动重试", "网络恢复后自动继续当前任务") {
        SettingSwitch("网络失败自动重试", retryEnabled) { enabled ->
            scope.launch { AgentSettings.setRetryEnabled(context, enabled) }
        }
        if (retryEnabled) {
            val attempts = retryAttemptsDraft.roundToInt().coerceIn(1, 10)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("最多重试", modifier = Modifier.weight(1f))
                Text("$attempts 次", style = MaterialTheme.typography.labelLarge)
            }
            Slider(
                value = retryAttemptsDraft,
                onValueChange = { retryAttemptsDraft = it },
                valueRange = 1f..10f,
                steps = 8,
                modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("1 次", style = MaterialTheme.typography.labelSmall)
                Text("10 次", style = MaterialTheme.typography.labelSmall)
            }
            if (attempts != retryAttempts) {
                Button(
                    onClick = { scope.launch { AgentSettings.setRetryAttempts(context, attempts) } },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Save, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("保存重试设置")
                }
            }
            Text(
                "仅对网络中断、超时、限流和上游临时错误触发；密钥或参数错误会直接提示。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    SettingsSection("结果判断", "手机 VPN 与中转站上游是两段独立链路") {
        Text("HTTP 403 表示服务器可达，但账户或出口地区被拒绝；HTTP 502 表示中转站上游暂时不可用；连接中断通常与代理分流或链路稳定性有关。")
        Text(
            "全局 VPN 会把微信等国内应用也送进代理。Metis 不能替其他应用改写 VPN 路由，请在 VPN 应用中启用分应用代理/绕过列表，把微信加入直连；否则 GPT 和微信无法同时稳定联网。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(
            onClick = {
                context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Settings, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("打开系统 VPN 设置")
        }
    }
}

@Composable
private fun CustomMetisSettingsPage(
    preset: String,
    onPreset: (String) -> Unit,
    instruction: String,
    onInstruction: (String) -> Unit,
    onSave: () -> Unit
) {
    SettingsSection("回复风格", "选择一个起始风格，也可以在下方补充自己的要求") {
        RadioSetting(
            "亲和",
            preset == AgentSettings.PERSONA_PRESET_FRIENDLY,
            "温暖、协作、贴心"
        ) { onPreset(AgentSettings.PERSONA_PRESET_FRIENDLY) }
        RadioSetting(
            "务实",
            preset == AgentSettings.PERSONA_PRESET_PRACTICAL,
            "简洁、专注、直接"
        ) { onPreset(AgentSettings.PERSONA_PRESET_PRACTICAL) }
    }
    SettingsSection("自定义指令", "这段指令会附加到三个模式的系统提示词中，工具规则和安全策略仍然优先") {
        OutlinedTextField(
            value = instruction,
            onValueChange = onInstruction,
            modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
            minLines = 5,
            maxLines = 10,
            placeholder = { Text("例如：回答先给结论，代码使用 Fira Code 风格，遇到不确定的信息先说明。") },
            label = { Text("Metis 回复指令") }
        )
    }
    Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Save, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("保存回复风格")
    }
}

@Composable
private fun CharacterCardsPage(
    cards: List<CharacterCard>,
    activeId: String,
    onImport: () -> Unit,
    onSelect: (String?) -> Unit,
    onSave: (CharacterCard) -> Unit,
    onDelete: (String) -> Unit,
    onNotice: (String) -> Unit
) {
    var editing by remember { mutableStateOf<CharacterCard?>(null) }

    SettingsSection(
        "角色卡",
        "每张卡可独立勾选「允许操作手机」。启用后该角色在对话中能操作真机；关闭后手机工具会被完全移除，角色只能对话。"
    ) {
        Button(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("导入角色卡（PNG / JSON）")
        }
    }

    if (cards.isEmpty()) {
        SettingsSection("", "") {
            Text(
                "还没有角色卡。可导入 SillyTavern / NativeTavern 导出的 PNG 或 JSON 角色卡，或导入后在此编辑。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        cards.forEach { card ->
            val isActive = card.id == activeId
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { editing = card },
                colors = CardDefaults.cardColors(
                    containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            card.name,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        if (isActive) {
                            AssistChip(onClick = {}, label = { Text("当前") })
                        }
                    }
                    Text(
                        card.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AssistChip(
                            onClick = {},
                            label = { Text(card.capabilityLabel()) }
                        )
                        if (card.source == "tavern") {
                            AssistChip(onClick = {}, label = { Text("已导入") })
                        }
                        Spacer(Modifier.weight(1f))
                        if (!isActive) {
                            TextButton(onClick = { onSelect(card.id) }) { Text("启用") }
                        } else {
                            TextButton(onClick = { onSelect(null) }) { Text("停用") }
                        }
                        TextButton(onClick = {
                            onDelete(card.id)
                            onNotice("角色卡「${card.name}」已删除")
                        }) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }

    editing?.let { card ->
        CharacterCardEditDialog(
            card = card,
            onDismiss = { editing = null },
            onSave = { updated ->
                onSave(updated)
                editing = null
                onNotice("角色卡「${updated.name}」已保存")
            }
        )
    }
}

@Composable
private fun CharacterCardEditDialog(
    card: CharacterCard,
    onDismiss: () -> Unit,
    onSave: (CharacterCard) -> Unit
) {
    var name by remember(card) { mutableStateOf(card.name) }
    var description by remember(card) { mutableStateOf(card.description) }
    var allowPhoneUse by remember(card) { mutableStateOf(card.allowPhoneUse) }
    var avatarUri by remember(card) { mutableStateOf(card.avatarUri) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑角色卡") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CharacterAvatarPicker(currentAvatarUri = avatarUri, onAvatarChanged = { avatarUri = it })
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("角色名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("角色设定") },
                    minLines = 4,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp)
                )
                Row(
                    Modifier.fillMaxWidth().clickable { allowPhoneUse = !allowPhoneUse },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "允许操作手机",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = allowPhoneUse, onCheckedChange = { allowPhoneUse = it })
                }
                Text(
                    if (allowPhoneUse) "开启后，该角色可在对话中操作手机执行真实动作。"
                    else "关闭后，该角色仅对话，手机操作工具会被移除。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    card.copy(
                        name = name.trim().ifBlank { "未命名角色" },
                        description = description.trim(),
                        allowPhoneUse = allowPhoneUse,
                        avatarUri = avatarUri
                    )
                )
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(96.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 4
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSelector(
    provider: ModelProvider,
    selectedModel: String,
    onSelected: (String) -> Unit,
    probedDeepSeekModels: List<String>? = null,
    probedGptModels: List<String>? = null
) {
    var expanded by remember { mutableStateOf(false) }
    val models = if (provider == ModelProvider.CUSTOM_OPENAI && !probedGptModels.isNullOrEmpty()) {
        (ModelCatalog.forProvider(provider) + probedGptModels).distinct().sorted()
    } else if (provider == ModelProvider.DEEPSEEK && !probedDeepSeekModels.isNullOrEmpty()) {
        ModelCatalog.normalizeDeepSeekModels(probedDeepSeekModels)
    } else {
        ModelCatalog.forProvider(provider)
    }
    ExposedDropdownMenuBox(expanded, { expanded = !expanded }) {
        OutlinedTextField(
            selectedModel, {}, readOnly = true, label = { Text("默认模型") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        ExposedDropdownMenu(expanded, { expanded = false }) {
            models.forEach { item ->
                DropdownMenuItem(text = { Text(item, maxLines = 1) }, onClick = { onSelected(item); expanded = false })
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, description: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        if (description.isNotBlank()) {
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        content()
        HorizontalDivider(Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun SecretField(value: String, onValueChange: (String) -> Unit, label: String, visible: Boolean, onToggle: () -> Unit) {
    OutlinedTextField(
        value, onValueChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = onToggle) {
                Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (visible) "隐藏" else "显示")
            }
        }
    )
}

@Composable
private fun StatusRow(title: String, detail: String, active: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            if (active) "可用" else "未就绪",
            style = MaterialTheme.typography.labelMedium,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun SettingSwitch(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title)
        Switch(checked, onCheckedChange)
    }
}

@Composable
private fun RadioSetting(title: String, selected: Boolean, detail: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick)
        Column(Modifier.weight(1f)) {
            Text(title)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
