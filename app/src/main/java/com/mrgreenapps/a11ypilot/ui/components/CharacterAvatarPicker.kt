package com.mrgreenapps.a11ypilot.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.core.content.FileProvider
import java.io.File

/**
 * 角色卡头像选择器：支持 摄像头 / 相册 / URL 三种来源，结果回传为可持久化的 URI 字符串。
 * 相册与摄像头结果会复制到应用私有目录，避免 content:// 授权过期。
 */
@Composable
fun CharacterAvatarPicker(
    currentAvatarUri: String,
    onAvatarChanged: (String) -> Unit
) {
    val context = LocalContext.current
    var showSourceMenu by remember { mutableStateOf(false) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }

    // 相册
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { onAvatarChanged(persistImage(context, it)) }
    }
    // 摄像头
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = cameraUri
        if (success && uri != null) {
            onAvatarChanged(persistImage(context, uri))
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // 头像预览
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { showSourceMenu = true },
            contentAlignment = Alignment.Center
        ) {
            if (currentAvatarUri.isNotBlank()) {
                AsyncImage(
                    model = currentAvatarUri,
                    contentDescription = "角色头像",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text("头像", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TextButton(onClick = { showSourceMenu = true }) {
            Text(if (currentAvatarUri.isBlank()) "设置头像" else "更换头像")
        }
    }

    // 来源选择菜单
    if (showSourceMenu) {
        AlertDialog(
            onDismissRequest = { showSourceMenu = false },
            title = { Text("选择头像来源") },
            text = {
                Column {
                    ListItem(
                        headlineContent = { Text("拍照") },
                        leadingContent = { Icon(Icons.Default.CameraAlt, null) },
                        modifier = Modifier.clickable {
                            showSourceMenu = false
                            val file = File.createTempFile("avatar-", ".jpg", context.cacheDir)
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                            cameraUri = uri
                            cameraLauncher.launch(uri)
                        }
                    )
                    ListItem(
                        headlineContent = { Text("从相册选择") },
                        leadingContent = { Icon(Icons.Default.PhotoLibrary, null) },
                        modifier = Modifier.clickable {
                            showSourceMenu = false
                            galleryLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                    )
                    ListItem(
                        headlineContent = { Text("使用图片链接") },
                        leadingContent = { Icon(Icons.Default.Link, null) },
                        modifier = Modifier.clickable {
                            showSourceMenu = false
                            showUrlDialog = true
                        }
                    )
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showSourceMenu = false }) { Text("取消") } }
        )
    }

    // URL 输入
    if (showUrlDialog) {
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showUrlDialog = false },
            title = { Text("图片链接") },
            text = {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("https://…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val trimmed = url.trim()
                    if (trimmed.isNotBlank()) {
                        onAvatarChanged(trimmed)
                    }
                    showUrlDialog = false
                }) { Text("确认") }
            },
            dismissButton = { TextButton(onClick = { showUrlDialog = false }) { Text("取消") } }
        )
    }
}

/** 把 content:// 或 file:// 的图片复制到应用私有目录，返回稳定的 file:// URI 字符串。 */
private fun persistImage(context: android.content.Context, source: Uri): String {
    return runCatching {
        val dir = File(context.filesDir, "character_avatars").apply { mkdirs() }
        val target = File(dir, "avatar_${System.currentTimeMillis()}.jpg")
        context.contentResolver.openInputStream(source)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        target.toURI().toString()
    }.getOrElse { source.toString() }
}
