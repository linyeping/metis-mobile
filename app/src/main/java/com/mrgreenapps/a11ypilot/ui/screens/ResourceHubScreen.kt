package com.mrgreenapps.a11ypilot.ui.screens

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.mrgreenapps.a11ypilot.tools.DocumentTool
import java.io.File

enum class WorkspacePanel(val title: String) {
    IMAGES("图片"),
    LIBRARY("资料库"),
    PROJECTS("项目")
}

private enum class LibraryFilter(val title: String) {
    ALL("全部"),
    AGENT("智能体"),
    USER("用户")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResourceHubScreen(
    panel: WorkspacePanel,
    onBack: () -> Unit,
    onOpenFile: (String) -> Unit
) {
    BackHandler(onBack = onBack)
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(panel.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "返回")
                    }
                }
            )
        }
    ) { padding ->
        when (panel) {
            WorkspacePanel.IMAGES -> ImagesPanel(Modifier.padding(padding), onOpenFile)
            WorkspacePanel.LIBRARY -> LibraryPanel(Modifier.padding(padding), onOpenFile)
            WorkspacePanel.PROJECTS -> ProjectsPanel(Modifier.padding(padding), onOpenFile)
        }
    }
}

@Composable
private fun ImagesPanel(modifier: Modifier, onOpenFile: (String) -> Unit) {
    val context = LocalContext.current
    val files = remember { generatedFiles(context) }
    FileListPanel(
        modifier = modifier,
        files = files,
        emptyTitle = "还没有生成图片",
        emptyDescription = "在输入栏的加号菜单中选择“图片生成”，生成的图片会保存在这里。",
        onOpenFile = onOpenFile
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun LibraryPanel(modifier: Modifier, onOpenFile: (String) -> Unit) {
    val context = LocalContext.current
    var filter by rememberSaveable { mutableStateOf(LibraryFilter.ALL) }
    val files = remember { libraryFiles(context) }
    val filtered = files.filter { file ->
        when (filter) {
            LibraryFilter.ALL -> true
            LibraryFilter.AGENT -> file.parentFile?.name == "generated"
            LibraryFilter.USER -> file.parentFile?.name == "attachments"
        }
    }
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            LibraryFilter.entries.forEachIndexed { index, item ->
                SegmentedButton(
                    selected = filter == item,
                    onClick = { filter = item },
                    shape = SegmentedButtonDefaults.itemShape(index, LibraryFilter.entries.size),
                    label = { Text(item.title) }
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        FileListPanel(
            modifier = Modifier.weight(1f),
            files = filtered,
            emptyTitle = "资料库为空",
            emptyDescription = "智能体生成的文件和你上传的附件会显示在这里。",
            onOpenFile = onOpenFile
        )
    }
}

@Composable
private fun ProjectsPanel(modifier: Modifier, onOpenFile: (String) -> Unit) {
    val context = LocalContext.current
    var workspace by remember { mutableStateOf(DocumentTool.workspaceTreeUri(context)) }
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null && DocumentTool.setWorkspaceTree(context, uri)) {
            workspace = uri
        }
    }
    val files = remember { workspace?.let { emptyList<File>() } ?: emptyList() }.orEmpty()
    Column(
        modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.WorkOutline, null, tint = MaterialTheme.colorScheme.primary)
                Text("手机工作区", style = MaterialTheme.typography.titleLarge)
                Text(
                    workspace?.toString() ?: "尚未选择工作区",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "项目会话会在这里使用同一个文件上下文，适合连续编写、运行和验证代码。",
                    style = MaterialTheme.typography.bodyMedium
                )
                FilledTonalButton(
                    onClick = { folderPicker.launch(null) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.FolderOpen, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (workspace == null) "选择手机工作区" else "更换手机工作区")
                }
            }
        }
        if (files.isNotEmpty()) FileListPanel(Modifier.weight(1f), files, "项目为空", "", onOpenFile)
    }
}

@Composable
private fun FileListPanel(
    modifier: Modifier,
    files: List<File>,
    emptyTitle: String,
    emptyDescription: String,
    onOpenFile: (String) -> Unit
) {
    // Local refreshable copy so deletions update the list without a full reload.
    var current by remember(files) { mutableStateOf(files) }
    var pendingDelete by remember { mutableStateOf<File?>(null) }

    if (current.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                Text(emptyTitle, style = MaterialTheme.typography.titleMedium)
                if (emptyDescription.isNotBlank()) {
                    Text(emptyDescription, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        return
    }

    pendingDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除文件？") },
            text = { Text("文件“${file.name}”将被删除，此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { file.delete() }
                    current = current.filterNot { it.absolutePath == file.absolutePath }
                    pendingDelete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } }
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(current, key = { it.absolutePath }) { file ->
            ListItem(
                modifier = Modifier.clickable { onOpenFile(file.absolutePath) },
                leadingContent = {
                    if (file.isImageFile()) {
                        AsyncImage(
                            model = file,
                            contentDescription = file.name,
                            modifier = Modifier.size(56.dp),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(Icons.Default.InsertDriveFile, null, modifier = Modifier.size(40.dp))
                    }
                },
                headlineContent = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(file.parentFile?.name ?: "Metis", maxLines = 1) },
                trailingContent = {
                    Row {
                        Icon(Icons.Default.OpenInNew, "预览", modifier = Modifier.clickable { onOpenFile(file.absolutePath) })
                        Spacer(Modifier.width(12.dp))
                        Icon(
                            Icons.Default.Delete, "删除",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.clickable { pendingDelete = file }
                        )
                    }
                }
            )
        }
    }
}

private fun generatedFiles(context: Context): List<File> =
    File(context.getExternalFilesDir(null) ?: context.filesDir, "Documents/Metis/generated")
        .takeIf { it.exists() }
        ?.walkTopDown()
        ?.filter { it.isFile && it.isImageFile() }
        ?.toList()
        ?.sortedByDescending { it.lastModified() }
        .orEmpty()

private fun libraryFiles(context: Context): List<File> {
    val root = File(context.getExternalFilesDir(null) ?: context.filesDir, "Documents/Metis")
    return root.takeIf { it.exists() }
        ?.walkTopDown()
        ?.filter { it.isFile }
        ?.toList()
        ?.sortedByDescending { it.lastModified() }
        .orEmpty()
}

private fun File.isImageFile(): Boolean = extension.lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif")
