package com.mrgreenapps.a11ypilot.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

@Composable
fun FileAttachment(
    filePath: String,
    onPreview: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val file = File(filePath)
    val extension = file.extension.lowercase(Locale.ROOT)
    val exists = file.isFile
    val previewable = extension in PREVIEW_EXTENSIONS
    val icon = when (extension) {
        "pdf" -> Icons.Default.PictureAsPdf
        "doc", "docx" -> Icons.Default.Description
        "md", "txt" -> Icons.Default.Article
        "jpg", "jpeg", "png", "gif", "webp", "bmp" -> Icons.Default.Image
        else -> Icons.Default.InsertDriveFile
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = exists) {
                if (previewable) onPreview() else openFileExternally(context, file)
            },
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        border = ButtonDefaults.outlinedButtonBorder
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = if (exists) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name.ifBlank { filePath },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (exists) {
                        "${formatFileSize(file.length())} · ${extension.uppercase(Locale.ROOT)} · ${if (previewable) "点击预览" else "点击打开"}"
                    } else {
                        "文件不存在"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (exists) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }
            if (previewable && exists) {
                Icon(Icons.Default.ChevronRight, "预览")
            }
            IconButton(
                onClick = { openFileExternally(context, file) },
                enabled = exists
            ) {
                Icon(Icons.Default.OpenInNew, "用其他应用打开")
            }
        }
    }
}

fun openFileExternally(context: Context, file: File) {
    if (!file.isFile) {
        Toast.makeText(context, "文件不存在", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, fileMimeType(file.extension))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "打开 ${file.name}"))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "没有可打开此文件的应用", Toast.LENGTH_SHORT).show()
    } catch (error: Exception) {
        Toast.makeText(context, "打开失败：${error.message}", Toast.LENGTH_SHORT).show()
    }
}

internal fun fileMimeType(extension: String): String = when (extension.lowercase(Locale.ROOT)) {
    "pdf" -> "application/pdf"
    "doc" -> "application/msword"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "md" -> "text/markdown"
    "txt", "json", "xml", "kt", "java", "py", "js", "html", "css" -> "text/plain"
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase(Locale.ROOT)) ?: "*/*"
}

internal fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.SIMPLIFIED_CHINESE, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> String.format(Locale.SIMPLIFIED_CHINESE, "%.1f MB", bytes / (1024.0 * 1024.0))
    else -> String.format(Locale.SIMPLIFIED_CHINESE, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}

private val PREVIEW_EXTENSIONS = setOf(
    "pdf", "docx", "md", "txt", "json", "xml", "kt", "java", "py", "js", "html", "css",
    "jpg", "jpeg", "png", "gif", "webp", "bmp"
)
