package com.mrgreenapps.a11ypilot.ui.screens

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.content.ContentValues
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.widget.Toast
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mrgreenapps.a11ypilot.tools.DocumentTool
import com.mrgreenapps.a11ypilot.ui.components.MarkdownMessage
import com.mrgreenapps.a11ypilot.ui.components.openFileExternally
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePreviewScreen(filePath: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val file = remember(filePath) { File(filePath) }
    val extension = file.extension.lowercase()

    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(file.name, maxLines = 1)
                        Text(
                            text = formatLabel(extension),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { saveFileToDownloads(context, file) }, enabled = file.isFile) {
                        Icon(Icons.Default.Download, "下载")
                    }
                    IconButton(onClick = { shareFile(context, file) }, enabled = file.isFile) {
                        Icon(Icons.Default.Share, "分享")
                    }
                    IconButton(onClick = { openFileExternally(context, file) }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, "用其他应用打开")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            when {
                !file.isFile -> PreviewError("文件不存在或已被移动")
                extension == "pdf" -> PdfPreview(file)
                extension == "docx" -> DocxPreview(file)
                extension == "md" -> TextPreview(file, markdown = true)
                extension in TEXT_EXTENSIONS -> TextPreview(file, markdown = false)
                extension in IMAGE_EXTENSIONS -> AsyncImage(
                    model = file,
                    contentDescription = file.name,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    contentScale = ContentScale.Fit
                )
                else -> PreviewError("暂不支持在应用内预览 .$extension，请使用右上角外部打开")
            }
        }
    }
}

@Composable
private fun TextPreview(file: File, markdown: Boolean) {
    val result by produceState<Result<String>?>(initialValue = null, file.path) {
        value = withContext(Dispatchers.IO) { runCatching { file.readText(Charsets.UTF_8) } }
    }
    when (val current = result) {
        null -> CircularProgressIndicator()
        else -> current.fold(
            onSuccess = { content ->
                if (markdown) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = 760.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        MarkdownMessage(content = content, modifier = Modifier.fillMaxWidth())
                    }
                } else {
                    SelectionContainer {
                        Text(
                            text = content,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxSize()
                                .widthIn(max = 760.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp)
                        )
                    }
                }
            },
            onFailure = { PreviewError("读取失败：${it.message}") }
        )
    }
}

@Composable
private fun DocxPreview(file: File) {
    val result by produceState<Result<String>?>(initialValue = null, file.path) {
        value = withContext(Dispatchers.IO) { runCatching { DocumentTool.readDocx(file) } }
    }
    when (val current = result) {
        null -> CircularProgressIndicator()
        else -> current.fold(
            onSuccess = { content ->
                SelectionContainer {
                    Text(
                        text = content.ifBlank { "Word 文档没有可提取的文本" },
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = 760.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp)
                    )
                }
            },
            onFailure = { PreviewError("Word 解析失败：${it.message}") }
        )
    }
}

@Composable
private fun PdfPreview(file: File) {
    val pageCountResult by produceState<Result<Int>?>(initialValue = null, file.path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { it.pageCount }
                }
            }
        }
    }
    when (val current = pageCountResult) {
        null -> CircularProgressIndicator()
        else -> current.fold(
            onSuccess = { pageCount ->
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 760.dp),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items((0 until pageCount.coerceAtMost(30)).toList()) { index ->
                        PdfPage(file = file, pageIndex = index)
                    }
                    if (pageCount > 30) {
                        item { Text("文档共 $pageCount 页，应用内显示前 30 页。") }
                    }
                }
            },
            onFailure = { PreviewError("PDF 解析失败：${it.message}") }
        )
    }
}

@Composable
private fun PdfPage(file: File, pageIndex: Int) {
    val bitmapResult by produceState<Result<Bitmap>?>(initialValue = null, file.path, pageIndex) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        renderer.openPage(pageIndex).use { page ->
                            val width = 1200
                            val height = (width * page.height.toFloat() / page.width).toInt().coerceAtLeast(1)
                            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                                bitmap.eraseColor(android.graphics.Color.WHITE)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                        }
                    }
                }
            }
        }
    }
    when (val current = bitmapResult) {
        null -> Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        else -> current.fold(
            onSuccess = { bitmap ->
                Surface(tonalElevation = 1.dp) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "第 ${pageIndex + 1} 页",
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.FillWidth
                    )
                }
            },
            onFailure = { PreviewError("第 ${pageIndex + 1} 页渲染失败") }
        )
    }
}

@Composable
private fun PreviewError(message: String) {
    Text(
        text = message,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(24.dp)
    )
}

private fun formatLabel(extension: String): String = when (extension) {
    "pdf" -> "PDF 文档"
    "docx" -> "Word 文档"
    "md" -> "Markdown 文档"
    in IMAGE_EXTENSIONS -> "图片"
    else -> "文本文件"
}

private fun shareFile(context: android.content.Context, file: File) {
    if (!file.isFile) return
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = com.mrgreenapps.a11ypilot.ui.components.fileMimeType(file.extension)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享 ${file.name}"))
    }.onFailure { Toast.makeText(context, "分享失败：${it.message}", Toast.LENGTH_SHORT).show() }
}

private fun saveFileToDownloads(context: android.content.Context, file: File) {
    if (!file.isFile) return
    runCatching {
        val resolver = context.contentResolver
        val mime = com.mrgreenapps.a11ypilot.ui.components.fileMimeType(file.extension)
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (mime.startsWith("image/")) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    if (mime.startsWith("image/")) "Pictures/Metis" else "Download/Metis"
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val target = resolver.insert(collection, values) ?: error("无法创建下载文件")
        try {
            resolver.openOutputStream(target)?.use { output -> file.inputStream().use { it.copyTo(output) } }
                ?: error("无法写入下载文件")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
        } catch (error: Throwable) {
            resolver.delete(target, null, null)
            throw error
        }
        Toast.makeText(context, "已保存到 ${if (mime.startsWith("image/")) "图片" else "下载"}", Toast.LENGTH_SHORT).show()
    }.onFailure { Toast.makeText(context, "下载失败：${it.message}", Toast.LENGTH_SHORT).show() }
}

private val TEXT_EXTENSIONS = setOf("txt", "json", "xml", "kt", "java", "py", "js", "html", "css")
private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
