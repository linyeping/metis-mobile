package com.mrgreenapps.a11ypilot.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mrgreenapps.a11ypilot.agent.AgentSettings
import com.mrgreenapps.a11ypilot.agent.CharacterCard
import com.mrgreenapps.a11ypilot.ui.components.CharacterAvatar

/**
 * 角色对话入口：列出所有角色卡，点一张就新建一个绑定该角色的会话。
 * 每张卡独立显示其「操作手机」能力；无卡时引导去设置页导入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterChatScreen(
    onStartChat: (String) -> Unit,
    onManage: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val cards by AgentSettings.characterCards(context).collectAsState(initial = emptyList())
    BackHandler(onBack = onBack)

    // 首次启动时写入内置角色卡（仅在存储为空时执行）。
    LaunchedEffect(Unit) { AgentSettings.ensureSeededCharacterCards(context) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("角色") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Text(
                "选择一个角色开始对话。角色会以自己的人设和你聊天；若允许操作手机，还能在对话中帮你执行真实设备动作。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp)
            )

            // 群聊提示：任意会话中 @角色名 即可让多个角色同框接力
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Chat, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(Modifier.width(6.dp))
                        Text("群聊模式", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Text(
                        "在任意会话里输入 @角色名 就能让指定角色发言，例如「@肖月 @小明 帮我规划周末」。同时 @ 多个角色会进入群聊，各自按人设轮流回应。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            if (cards.isEmpty()) {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.Face, null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Text("还没有角色卡", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "去设置 → 个性化 → 角色卡 导入或创建一张，就能在这里和角色对话。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onManage) {
                        Icon(Icons.Default.Settings, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("去管理角色卡")
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(cards, key = { it.id }) { card ->
                        CharacterCardRow(card = card, onStart = { onStartChat(card.id) })
                    }
                }
            }
        }
    }
}

/**
 * 角色卡行：只展示头像、名字、「可操作手机」能力标签和「开始对话」按钮，
 * 不再铺开角色设定原文（内容通常很长，列表里反而淹没重点）。
 */
@Composable
private fun CharacterCardRow(card: CharacterCard, onStart: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onStart)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CharacterAvatar(card = card, size = 44.dp, corner = 12.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    card.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text(card.capabilityLabel()) }
                )
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onStart, contentPadding = PaddingValues(horizontal = 12.dp)) {
                Icon(Icons.Default.Chat, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("开始对话")
            }
        }
    }
}
