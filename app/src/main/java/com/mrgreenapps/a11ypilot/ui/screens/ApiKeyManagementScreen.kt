package com.mrgreenapps.a11ypilot.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.mrgreenapps.a11ypilot.agent.AgentSettings
import com.mrgreenapps.a11ypilot.agent.ApiKeyRouter
import com.mrgreenapps.a11ypilot.agent.BalanceProbe
import com.mrgreenapps.a11ypilot.agent.DeepSeekModelProbe
import com.mrgreenapps.a11ypilot.agent.OpenAIModelProbe
import com.mrgreenapps.a11ypilot.data.ApiKeyProfile
import com.mrgreenapps.a11ypilot.data.ApiKeyProfileProvider
import com.mrgreenapps.a11ypilot.data.ApiKeyProfileRecord
import com.mrgreenapps.a11ypilot.data.ApiKeyRepository
import com.mrgreenapps.a11ypilot.data.ApiKeyBalanceStatus
import com.mrgreenapps.a11ypilot.data.ApiKeyTestStatus
import com.mrgreenapps.a11ypilot.data.UsageEntry
import com.mrgreenapps.a11ypilot.data.UsageRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

@Composable
fun ApiKeyManagementScreen(onNotice: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val profiles by ApiKeyRepository.observeProfiles(context).collectAsState(initial = emptyList())
    val usageEntries by UsageRepository.observe(context).collectAsState(initial = emptyList())
    val rotationStrategy by AgentSettings.apiKeyRotationStrategy(context).collectAsState(initial = ApiKeyRouter.Strategy.HEALTH_FIRST)
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<ApiKeyDraft?>(null) }
    var deleteTarget by remember { mutableStateOf<ApiKeyProfile?>(null) }
    var testingId by rememberSaveable { mutableStateOf<String?>(null) }
    var balanceRefreshingId by rememberSaveable { mutableStateOf<String?>(null) }
    var showKeyIds by remember { mutableStateOf(setOf<String>()) }
    var strategyMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        ApiKeyRepository.ensureSeeded(context)
        AgentSettings.ensureSeededCharacterCards(context)
    }

    suspend fun refreshBalance(profile: ApiKeyProfile) {
        if (profile.apiKey.isBlank()) return
        ApiKeyRepository.updateBalance(context, profile.id, ApiKeyBalanceStatus.FETCHING)
        runCatching {
            when (profile.provider) {
                ApiKeyProfileProvider.DEEPSEEK -> BalanceProbe.probeDeepSeek(profile.apiKey)
                ApiKeyProfileProvider.RELAY,
                ApiKeyProfileProvider.CUSTOM -> BalanceProbe.probeRelay(profile.baseUrl, profile.apiKey)
            }
        }.onSuccess { balance ->
            ApiKeyRepository.updateBalance(
                context,
                profile.id,
                ApiKeyBalanceStatus.SUCCESS,
                amount = balance.amount,
                currency = balance.currency
            )
        }.onFailure { error ->
            ApiKeyRepository.updateBalance(
                context,
                profile.id,
                ApiKeyBalanceStatus.FAILED,
                message = error.message.orEmpty()
            )
        }
    }

    LaunchedEffect(Unit) {
        ApiKeyRepository.ensureSeeded(context)
        ApiKeyRepository.observeProfiles(context).first()
            .filter { it.apiKey.isNotBlank() }
            .forEach { refreshBalance(it) }
    }

    fun startEditing(profile: ApiKeyProfile) {
        expandedId = profile.id
        editing = ApiKeyDraft(profile.record, profile.apiKey)
    }

    fun testProfile(profile: ApiKeyProfile) {
        if (testingId != null) return
        scope.launch {
            testingId = profile.id
            ApiKeyRepository.updateProbe(context, profile.id, ApiKeyTestStatus.TESTING, profile.models)
            val result = runCatching {
                when (profile.provider) {
                    ApiKeyProfileProvider.DEEPSEEK -> DeepSeekModelProbe.probe(profile.apiKey)
                    ApiKeyProfileProvider.RELAY,
                    ApiKeyProfileProvider.CUSTOM -> OpenAIModelProbe.probe(profile.baseUrl, profile.apiKey)
                }
            }
            result.onSuccess { models ->
                ApiKeyRepository.updateProbe(context, profile.id, ApiKeyTestStatus.SUCCESS, models)
                onNotice("${profile.label} 连接成功，发现 ${models.size} 个模型")
            }.onFailure { error ->
                ApiKeyRepository.updateProbe(context, profile.id, ApiKeyTestStatus.FAILED, profile.models, error.message.orEmpty())
                onNotice("${profile.label} 连接失败：${error.message ?: "未知错误"}")
            }
            testingId = null
        }
    }

    fun refreshProfileBalance(profile: ApiKeyProfile) {
        if (balanceRefreshingId != null || profile.apiKey.isBlank()) return
        scope.launch {
            balanceRefreshingId = profile.id
            refreshBalance(profile)
            balanceRefreshingId = null
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("管理不同服务商的密钥、BaseURL 和探针状态。密钥本体使用加密存储。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("多密钥智能轮询", style = MaterialTheme.typography.titleSmall)
                Text("遇到限流、无效 Key 或网络故障时自动切换。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                OutlinedButton(onClick = { strategyMenuExpanded = true }) {
                    Text(rotationStrategy.label())
                }
                DropdownMenu(
                    expanded = strategyMenuExpanded,
                    onDismissRequest = { strategyMenuExpanded = false }
                ) {
                    ApiKeyRouter.Strategy.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label()) },
                            onClick = {
                                strategyMenuExpanded = false
                                scope.launch { AgentSettings.setApiKeyRotationStrategy(context, option) }
                            }
                        )
                    }
                }
            }
        }
        Text("已配置的 API 密钥 (${profiles.count { it.apiKey.isNotBlank() }})", style = MaterialTheme.typography.titleMedium)
        if (profiles.isEmpty()) {
            Text("还没有密钥档案，点击下方按钮添加。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        profiles.forEach { profile ->
            val isExpanded = expandedId == profile.id
            val profileUsage = usageEntries.filter { matchesProvider(profile.provider, it) }
            if (editing?.record?.id == profile.id) {
                ApiKeyEditCard(
                    draft = editing!!,
                    showKey = profile.id in showKeyIds,
                    onToggleKey = { showKeyIds = toggleSet(showKeyIds, profile.id) },
                    onChange = { editing = it },
                    onSave = {
                        scope.launch {
                            ApiKeyRepository.save(context, it.record, it.apiKey)
                            editing = null
                            onNotice("${it.record.label} 已保存")
                        }
                    },
                    onCancel = { editing = null }
                )
            } else {
                ApiKeyCard(
                    profile = profile,
                    expanded = isExpanded,
                    testing = testingId == profile.id,
                    refreshingBalance = balanceRefreshingId == profile.id || profile.balanceStatus == ApiKeyBalanceStatus.FETCHING,
                    usage = profileUsage,
                    onToggle = { expandedId = if (isExpanded) null else profile.id },
                    onEdit = { startEditing(profile) },
                    onRefreshBalance = { refreshProfileBalance(profile) },
                    onTest = { testProfile(profile) },
                    onDelete = { deleteTarget = profile }
                )
            }
        }
        FilledTonalButton(
            onClick = {
                scope.launch {
                    val record = ApiKeyRepository.createCustom(context)
                    expandedId = record.id
                    editing = ApiKeyDraft(record, "")
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Add, null)
            Spacer(Modifier.width(8.dp))
            Text("添加新密钥")
        }
    }

    deleteTarget?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除密钥？") },
            text = { Text("将删除“${profile.label}”的档案和加密保存的密钥。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        ApiKeyRepository.delete(context, profile.id)
                        if (expandedId == profile.id) expandedId = null
                        deleteTarget = null
                        onNotice("密钥已删除")
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }
}

private fun ApiKeyRouter.Strategy.label(): String = when (this) {
    ApiKeyRouter.Strategy.ROUND_ROBIN -> "轮询"
    ApiKeyRouter.Strategy.FAILOVER -> "故障转移"
    ApiKeyRouter.Strategy.HEALTH_FIRST -> "健康优先"
}

private data class ApiKeyDraft(val record: ApiKeyProfileRecord, val apiKey: String)

@Composable
private fun ApiKeyCard(
    profile: ApiKeyProfile,
    expanded: Boolean,
    testing: Boolean,
    refreshingBalance: Boolean,
    usage: List<UsageEntry>,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onRefreshBalance: () -> Unit,
    onTest: () -> Unit,
    onDelete: () -> Unit
) {
    val totalTokens = usage.sumOf { it.totalTokens }
    val weekTokens = usage.filter { System.currentTimeMillis() - it.timestamp <= TimeUnit.DAYS.toMillis(7) }.sumOf { it.totalTokens }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Key, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(profile.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Icon(if (expanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown, if (expanded) "折叠" else "展开")
            }
            Text(maskKey(profile.apiKey), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            StatusLine(profile.status, testing, profile.models.size, profile.statusMessage)
            // Keep the provider balance visible in the compact card as well as the detail view.
            BalanceLine(profile)
            if (expanded) {
                HorizontalDivider()
                Text("BaseURL", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(profile.baseUrl.ifBlank { "未配置" }, style = MaterialTheme.typography.bodyMedium)
                Text("可用模型", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (profile.models.isEmpty()) Text("尚未探测模型", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else profile.models.take(8).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                Text("累计 Token：${formatCount(totalTokens)} · 近 7 天：${formatCount(weekTokens)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (profile.note.isNotBlank()) Text("备注：${profile.note}", style = MaterialTheme.typography.bodySmall)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onRefreshBalance,
                        enabled = !refreshingBalance && profile.apiKey.isNotBlank(),
                        modifier = Modifier.weight(1f).defaultMinSize(minWidth = 0.dp, minHeight = 34.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp)
                    ) {
                        if (refreshingBalance) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Refresh, "刷新余额", Modifier.size(16.dp))
                        Spacer(Modifier.width(2.dp)); Text("余额", style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(
                        onClick = onEdit,
                        modifier = Modifier.weight(1f).defaultMinSize(minWidth = 0.dp, minHeight = 34.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp)
                    ) {
                        Icon(Icons.Default.Edit, null, Modifier.size(16.dp)); Spacer(Modifier.width(2.dp)); Text("编辑", style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(
                        onClick = onTest,
                        enabled = !testing,
                        modifier = Modifier.weight(1.35f).defaultMinSize(minWidth = 0.dp, minHeight = 34.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp)
                    ) {
                        if (testing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(2.dp)); Text("测试连接", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                    TextButton(
                        onClick = onDelete,
                        modifier = Modifier.weight(1f).defaultMinSize(minWidth = 0.dp, minHeight = 34.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp)
                    ) {
                        Icon(Icons.Default.Delete, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error); Spacer(Modifier.width(2.dp)); Text("删除", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun BalanceLine(profile: ApiKeyProfile) {
    val text = when (profile.balanceStatus) {
        ApiKeyBalanceStatus.SUCCESS -> {
            val currency = profile.balanceCurrency.orEmpty().ifBlank { "" }
            "余额：${listOf(currency, profile.balanceAmount.orEmpty()).filter(String::isNotBlank).joinToString(" ")}"
        }
        ApiKeyBalanceStatus.FETCHING -> "余额：查询中..."
        ApiKeyBalanceStatus.FAILED -> "余额：查询失败${profile.balanceMessage.takeIf { it.isNotBlank() }?.let { " · ${it.take(80)}" }.orEmpty()}"
        ApiKeyBalanceStatus.NEVER_FETCHED -> "余额：尚未查询"
    }
    val color = when (profile.balanceStatus) {
        ApiKeyBalanceStatus.SUCCESS -> Color(0xFF2E7D32)
        ApiKeyBalanceStatus.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
}

@Composable
private fun ApiKeyEditCard(
    draft: ApiKeyDraft,
    showKey: Boolean,
    onToggleKey: () -> Unit,
    onChange: (ApiKeyDraft) -> Unit,
    onSave: (ApiKeyDraft) -> Unit,
    onCancel: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Key, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(draft.record.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(draft.record.provider.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(
                value = draft.record.baseUrl,
                onValueChange = { onChange(draft.copy(record = draft.record.copy(baseUrl = it))) },
                label = { Text("BaseURL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = draft.apiKey,
                onValueChange = { onChange(draft.copy(apiKey = it)) },
                label = { Text("API 密钥") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { IconButton(onClick = onToggleKey) { Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, "显示密钥") } }
            )
            OutlinedTextField(
                value = draft.record.note,
                onValueChange = { onChange(draft.copy(record = draft.record.copy(note = it.take(60)))) },
                label = { Text("备注（可选）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSave(draft) }, modifier = Modifier.weight(1f)) { Text("保存") }
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("取消") }
            }
        }
    }
}

@Composable
private fun StatusLine(status: ApiKeyTestStatus, testing: Boolean, modelCount: Int, message: String) {
    val effective = if (testing) ApiKeyTestStatus.TESTING else status
    val (icon, text, color) = when (effective) {
        ApiKeyTestStatus.SUCCESS -> Triple(Icons.Default.CheckCircle, "${modelCount} 个模型可用", Color(0xFF2E7D32))
        ApiKeyTestStatus.FAILED -> Triple(Icons.Default.Error, "连接失败", MaterialTheme.colorScheme.error)
        ApiKeyTestStatus.TESTING -> Triple(Icons.Default.Refresh, "测试中...", MaterialTheme.colorScheme.primary)
        ApiKeyTestStatus.NEVER_TESTED -> Triple(Icons.Default.Error, "需要测试连接", MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(5.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
        if (effective == ApiKeyTestStatus.FAILED && message.isNotBlank()) {
            Text(" · ${message.take(48)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 1)
        }
    }
}

private fun matchesProvider(provider: ApiKeyProfileProvider, entry: UsageEntry): Boolean = when (provider) {
    ApiKeyProfileProvider.RELAY, ApiKeyProfileProvider.CUSTOM -> entry.provider != com.mrgreenapps.a11ypilot.data.ModelProvider.DEEPSEEK
    ApiKeyProfileProvider.DEEPSEEK -> entry.provider == com.mrgreenapps.a11ypilot.data.ModelProvider.DEEPSEEK
}

private fun maskKey(key: String): String = if (key.isBlank()) "未配置 API 密钥" else "••••••••${key.takeLast(4)}"

private fun formatCount(value: Long): String = when {
    value >= 1_000_000 -> "%.1fM".format(value / 1_000_000f)
    value >= 1_000 -> "%.1fk".format(value / 1_000f)
    else -> value.toString()
}

private fun toggleSet(values: Set<String>, value: String): Set<String> =
    if (value in values) values - value else values + value
