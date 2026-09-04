package com.mrgreenapps.a11ypilot.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WorkOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mrgreenapps.a11ypilot.agent.AgentSettings
import com.mrgreenapps.a11ypilot.agent.CharacterCard
import com.mrgreenapps.a11ypilot.data.Session
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun SessionDrawer(
    sessions: List<Session>,
    activeSessionId: String?,
    characterCards: List<CharacterCard>,
    onCreateSession: () -> Unit,
    onSelectSession: (String) -> Unit,
    onRenameSession: (String, String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onTogglePinSession: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenImages: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenProjects: () -> Unit,
    onOpenAutomation: () -> Unit,
    onOpenRemote: () -> Unit,
    onOpenCharacters: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val collapsedSections by AgentSettings.collapsedSections(context).collectAsState(initial = emptySet())
    val drawerWidth = minOf(LocalConfiguration.current.screenWidthDp.dp * 0.60f, 360.dp)
    ModalDrawerSheet(
        modifier = Modifier.width(drawerWidth)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text("Metis", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 10.dp))
            DrawerFeatureItem(Icons.Default.Image, "图片", onOpenImages)
            DrawerFeatureItem(Icons.Default.FolderOpen, "资料库", onOpenLibrary)
            DrawerFeatureItem(Icons.Default.WorkOutline, "项目", onOpenProjects)
            DrawerFeatureItem(Icons.Default.Alarm, "自动化任务", onOpenAutomation)
            DrawerFeatureItem(Icons.Default.Face, "角色", onOpenCharacters)
            DrawerFeatureItem(Icons.Default.Devices, "远程指挥", onOpenRemote)
            HorizontalDivider(Modifier.padding(vertical = 10.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Group sessions: show above the character sessions so users can scan multi-member chats
                // first. A group session has non-empty groupMemberIds (per GroupCoordinator contract).
                val groupSessions = sessions.filter { it.groupMemberIds.isNotEmpty() }
                if (groupSessions.isNotEmpty()) {
                    item {
                        CollapsibleSectionHeader(
                            title = "群组对话",
                            collapsed = "群组对话" in collapsedSections,
                            isPrimary = true,
                            onToggle = { scope.launch { AgentSettings.setSectionCollapsed(context, "群组对话", !("群组对话" in collapsedSections)) } }
                        )
                    }
                    if ("群组对话" !in collapsedSections) {
                        items(groupSessions, key = { it.id }) { session ->
                            GroupSessionItem(
                                session = session,
                                members = session.groupMemberIds.mapNotNull { id -> characterCards.firstOrNull { it.id == id } },
                                isActive = session.id == activeSessionId,
                                onClick = { onSelectSession(session.id) },
                                onRename = { onRenameSession(session.id, it) },
                                onDelete = { onDeleteSession(session.id) }
                            )
                        }
                    }
                }
                val characterSessions = sessions.filter { it.characterCardId != null }
                val pinned = sessions.filter { it.isPinned && it.characterCardId == null }
                val others = sessions.filter { !it.isPinned && it.characterCardId == null }

                if (characterSessions.isNotEmpty()) {
                    item {
                        CollapsibleSectionHeader(
                            title = "角色会话",
                            collapsed = "角色会话" in collapsedSections,
                            isPrimary = true,
                            onToggle = { scope.launch { AgentSettings.setSectionCollapsed(context, "角色会话", !("角色会话" in collapsedSections)) } }
                        )
                    }
                    if ("角色会话" !in collapsedSections) {
                        items(characterSessions, key = { it.id }) { session ->
                            val card = characterCards.firstOrNull { it.id == session.characterCardId }
                            CharacterSessionItem(
                                session = session,
                                card = card,
                                isActive = session.id == activeSessionId,
                                onClick = { onSelectSession(session.id) },
                                onRename = { onRenameSession(session.id, it) },
                                onDelete = { onDeleteSession(session.id) }
                            )
                        }
                    }
                }

                if (pinned.isNotEmpty()) {
                    item {
                        CollapsibleSectionHeader(
                            title = "已固定会话",
                            collapsed = "已固定会话" in collapsedSections,
                            isPrimary = true,
                            onToggle = { scope.launch { AgentSettings.setSectionCollapsed(context, "已固定会话", !("已固定会话" in collapsedSections)) } }
                        )
                    }
                    if ("已固定会话" !in collapsedSections) {
                        items(pinned, key = { it.id }) { session ->
                            SessionItem(
                                session = session,
                                isActive = session.id == activeSessionId,
                                onClick = { onSelectSession(session.id) },
                                onRename = { onRenameSession(session.id, it) },
                                onDelete = { onDeleteSession(session.id) },
                                onTogglePin = { onTogglePinSession(session.id) }
                            )
                        }
                    }
                }
                // 仅当上方存在内容（角色会话 / 已固定会话）时才画分隔线，避免出现孤立的双横线
                if (characterSessions.isNotEmpty() || pinned.isNotEmpty()) {
                    item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                }
                item {
                    CollapsibleSectionHeader(
                        title = "最近会话",
                        collapsed = "最近会话" in collapsedSections,
                        isPrimary = false,
                        onToggle = { scope.launch { AgentSettings.setSectionCollapsed(context, "最近会话", !("最近会话" in collapsedSections)) } }
                    )
                }
                if ("最近会话" !in collapsedSections) {
                    items(others, key = { it.id }) { session ->
                        SessionItem(
                            session = session,
                            isActive = session.id == activeSessionId,
                            onClick = { onSelectSession(session.id) },
                            onRename = { onRenameSession(session.id, it) },
                            onDelete = { onDeleteSession(session.id) },
                            onTogglePin = { onTogglePinSession(session.id) }
                        )
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onCreateSession,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Add, "新建会话", modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("聊天")
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Person, "打开设置")
                }
            }
        }
    }
}

@Composable
private fun CollapsibleSectionHeader(
    title: String,
    collapsed: Boolean,
    isPrimary: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = if (isPrimary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Icon(
            if (collapsed) Icons.Default.ChevronRight else Icons.Default.KeyboardArrowDown,
            contentDescription = if (collapsed) "展开" else "折叠",
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DrawerFeatureItem(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .height(42.dp),
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) },
        headlineContent = { Text(title, style = MaterialTheme.typography.bodyMedium) }
    )
}

@Composable
private fun CharacterSessionItem(
    session: Session,
    card: CharacterCard?,
    isActive: Boolean,
    onClick: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit
) {
    val cardName = card?.name ?: "角色"
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var editedTitle by remember(session.title) { mutableStateOf(session.title) }

    if (showRename) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("重命名会话") },
            text = {
                OutlinedTextField(
                    value = editedTitle,
                    onValueChange = { editedTitle = it.take(40) },
                    singleLine = true,
                    label = { Text("会话名称") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { onRename(editedTitle); showRename = false },
                    enabled = editedTitle.isNotBlank()
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("取消") } }
        )
    }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("删除会话？") },
            text = { Text("会话“${session.title}”及其历史消息将从列表中移除。") },
            confirmButton = {
                TextButton(onClick = { onDelete(); showDelete = false }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("取消") } }
        )
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = if (isActive)
            MaterialTheme.colorScheme.primaryContainer
        else
            MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp, end = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CharacterAvatar(card = card, size = 36.dp, corner = 8.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = cardName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    color = if (isActive)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = session.title,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    color = if (isActive)
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Default.MoreVert, "会话操作", modifier = Modifier.size(20.dp))
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                        onClick = { menuExpanded = false; editedTitle = session.title; showRename = true }
                    )
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menuExpanded = false; showDelete = true }
                    )
                }
            }
        }
    }
}

/**
 * 群组会话的抽屉条目：横向堆叠 2-4 个角色头像 + 「群组对话 · N 人」徽章，
 * 让用户在一堆会话里能秒识别「这个是多人群聊」。
 */
@Composable
private fun GroupSessionItem(
    session: Session,
    members: List<CharacterCard>,
    isActive: Boolean,
    onClick: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit
) {
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var editedTitle by remember(session.title) { mutableStateOf(session.title) }

    if (showRename) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("重命名会话") },
            text = {
                OutlinedTextField(
                    value = editedTitle,
                    onValueChange = { editedTitle = it.take(40) },
                    singleLine = true,
                    label = { Text("会话名称") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { onRename(editedTitle); showRename = false },
                    enabled = editedTitle.isNotBlank()
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("取消") } }
        )
    }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("删除会话？") },
            text = { Text("会话“${session.title}”及其历史消息将从列表中移除。") },
            confirmButton = {
                TextButton(onClick = { onDelete(); showDelete = false }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("取消") } }
        )
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = if (isActive) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp, end = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 角色头像堆叠：横向排列前 4 个成员，超过则用 +N 表示
            Box(
                modifier = Modifier
                    .height(36.dp)
                    .widthIn(min = 36.dp)
            ) {
                members.take(4).forEachIndexed { index, card ->
                    Box(
                        modifier = Modifier
                            .padding(start = (index * 18).dp)
                    ) {
                        CharacterAvatar(card = card, size = 36.dp, corner = 8.dp)
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = session.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        color = if (isActive)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    // 「群组对话 · N 人」徽章
                    Surface(
                        color = if (isActive)
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.18f)
                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "群组 · ${members.size} 人",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isActive)
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                        )
                    }
                }
                if (members.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = members.joinToString("、") { it.name },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        color = if (isActive)
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
            Box {
                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Default.MoreVert, "会话操作", modifier = Modifier.size(20.dp))
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                        onClick = { menuExpanded = false; editedTitle = session.title; showRename = true }
                    )
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menuExpanded = false; showDelete = true }
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionItem(
    session: Session,
    isActive: Boolean,
    onClick: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit
) {
    val dateFormat = SimpleDateFormat("MM/dd HH:mm", Locale.SIMPLIFIED_CHINESE)
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var editedTitle by remember(session.title) { mutableStateOf(session.title) }

    if (showRename) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("重命名会话") },
            text = {
                OutlinedTextField(
                    value = editedTitle,
                    onValueChange = { editedTitle = it.take(40) },
                    singleLine = true,
                    label = { Text("会话名称") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { onRename(editedTitle); showRename = false },
                    enabled = editedTitle.isNotBlank()
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("取消") } }
        )
    }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("删除会话？") },
            text = { Text("会话“${session.title}”及其历史消息将从列表中移除。") },
            confirmButton = {
                TextButton(onClick = { onDelete(); showDelete = false }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("取消") } }
        )
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = if (isActive)
            MaterialTheme.colorScheme.primaryContainer
        else
            MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp, end = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = session.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    color = if (isActive)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = dateFormat.format(Date(session.lastActiveAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isActive)
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Default.MoreVert, "会话操作", modifier = Modifier.size(20.dp))
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(if (session.isPinned) "取消固定" else "固定") },
                        leadingIcon = { Icon(Icons.Default.PushPin, null) },
                        onClick = { menuExpanded = false; onTogglePin() }
                    )
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                        onClick = { menuExpanded = false; editedTitle = session.title; showRename = true }
                    )
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menuExpanded = false; showDelete = true }
                    )
                }
            }
        }
    }
}
