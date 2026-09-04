package com.mrgreenapps.a11ypilot.tools

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import java.io.InputStream
import java.io.StringReader
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.SAXParserFactory

class DocumentTool(context: Context) {
    private val appContext = context.applicationContext
    private val contentResolver = appContext.contentResolver
    val baseDirectory: File = File(
        appContext.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: appContext.filesDir,
        "Metis"
    ).apply { mkdirs() }

    data class WriteResult(val file: File, val description: String)

    suspend fun write(path: String, content: String, format: String?): WriteResult =
        withContext(Dispatchers.IO) {
            require(!isWorkspacePath(path)) {
                "workspace/ 外部目录当前为只读，请把生成文件写入 Metis 文档目录"
            }
            val normalizedFormat = normalizeFormat(format, File(path).extension)
            val file = resolve(path.withExtensionFor(normalizedFormat))
            file.parentFile?.mkdirs()
            when (normalizedFormat) {
                "pdf" -> writePdf(file, content)
                "docx" -> writeDocx(file, content)
                else -> file.writeText(content, Charsets.UTF_8)
            }
            WriteResult(file, "已写入 ${file.name}（${file.length()} 字节）")
        }

    suspend fun read(path: String): String = withContext(Dispatchers.IO) {
        if (isWorkspacePath(path)) return@withContext readExternal(path)
        val file = resolve(path)
        require(file.isFile) { "文件不存在：$path" }
        when (file.extension.lowercase()) {
            "docx" -> readDocx(file)
            "pdf" -> "PDF 文件 ${file.name}，大小 ${file.length()} 字节。请通过附件预览。"
            else -> file.readText(Charsets.UTF_8)
        }.take(MAX_READ_CHARS)
    }

    suspend fun list(path: String = ""): String = withContext(Dispatchers.IO) {
        if (isWorkspacePath(path)) return@withContext listExternal(path)
        val directory = if (path.isBlank()) baseDirectory else resolve(path)
        require(directory.isDirectory) { "目录不存在：$path" }
        val files = directory.walkTopDown()
            .maxDepth(3)
            .filter { it != directory }
            .take(MAX_LIST_ITEMS)
            .map { file ->
                val relative = file.relativeTo(baseDirectory).invariantSeparatorsPath
                if (file.isDirectory) "[目录] $relative" else "[文件] $relative (${file.length()} 字节)"
            }
            .toList()
        if (files.isEmpty()) "Metis 文档目录为空" else files.joinToString("\n")
    }

    fun resolve(path: String): File {
        val cleaned = path.trim().replace('\\', '/').trimStart('/')
        require(cleaned.isNotBlank()) { "文件路径不能为空" }
        val candidate = File(baseDirectory, cleaned).canonicalFile
        val base = baseDirectory.canonicalFile
        require(candidate.path == base.path || candidate.path.startsWith(base.path + File.separator)) {
            "文件路径必须位于 Metis 文档目录内"
        }
        return candidate
    }

    private fun readExternal(path: String): String {
        val document = findExternalDocument(externalRelativePath(path))
        val name = document.displayName
        return contentResolver.openInputStream(document.uri)?.use { input ->
            when (extensionOf(name)) {
                "docx" -> readDocx(input)
                "pdf" -> "PDF 文件 $name，大小 ${document.size ?: -1L} 字节。请通过附件预览。"
                else -> readTextLimited(input)
            }
        } ?: throw IllegalStateException("无法打开外部文件：$path")
    }

    private fun listExternal(path: String): String {
        val relative = externalRelativePath(path)
        val root = findExternalDocument(relative)
        require(root.isDirectory) { "外部路径不是目录：$path" }
        val lines = mutableListOf<String>()
        walkExternal(root, relative, 0, lines)
        return if (lines.isEmpty()) {
            "外部目录为空：${if (relative.isBlank()) "workspace" else "workspace/$relative"}"
        } else {
            lines.joinToString("\n")
        }
    }

    private fun walkExternal(
        directory: ExternalDocument,
        relative: String,
        depth: Int,
        output: MutableList<String>
    ) {
        if (depth > MAX_EXTERNAL_DEPTH || output.size >= MAX_LIST_ITEMS) return
        queryChildren(directory.uri).forEach { child ->
            if (output.size >= MAX_LIST_ITEMS) return@forEach
            val childRelative = listOf(relative, child.displayName)
                .filter { it.isNotBlank() }
                .joinToString("/")
            if (child.isDirectory) {
                output += "[目录] workspace/$childRelative"
                walkExternal(child, childRelative, depth + 1, output)
            } else {
                val size = child.size?.let { " (${it} 字节)" }.orEmpty()
                output += "[文件] workspace/$childRelative$size"
            }
        }
    }

    private fun findExternalDocument(relative: String): ExternalDocument {
        val treeUri = workspaceTreeUri(appContext)
            ?: throw IllegalStateException("尚未授权外部文件夹，请打开设置 > 文件访问")
        var current = ExternalDocument(
            uri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri)
            ),
            displayName = "workspace",
            isDirectory = true,
            size = null
        )
        relative.split('/').filter { it.isNotBlank() }.forEach { segment ->
            current = queryChildren(current.uri).firstOrNull { it.displayName == segment }
                ?: throw IllegalArgumentException("外部文件不存在：workspace/$relative")
        }
        return current
    }

    private fun queryChildren(parent: Uri): List<ExternalDocument> {
        val treeUri = workspaceTreeUri(appContext)
            ?: throw IllegalStateException("尚未授权外部文件夹，请打开设置 > 文件访问")
        val documentId = DocumentsContract.getDocumentId(parent)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
        return contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getString(idIndex)
                    val name = cursor.getString(nameIndex).orEmpty()
                    val mime = cursor.getString(mimeIndex).orEmpty()
                    add(
                        ExternalDocument(
                            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                            displayName = name,
                            isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                            size = if (cursor.isNull(sizeIndex)) null else cursor.getLong(sizeIndex)
                        )
                    )
                }
            }
        } ?: emptyList()
    }

    private fun readTextLimited(input: InputStream): String {
        val reader = input.bufferedReader(Charsets.UTF_8)
        val buffer = CharArray(MAX_READ_CHARS)
        val count = reader.read(buffer)
        return if (count <= 0) "" else String(buffer, 0, count)
    }

    private fun externalRelativePath(path: String): String {
        val cleaned = path.trim().replace('\\', '/').trimStart('/')
        require(cleaned == WORKSPACE_PREFIX || cleaned.startsWith("$WORKSPACE_PREFIX/")) {
            "外部路径必须使用 workspace/ 前缀"
        }
        return cleaned.removePrefix(WORKSPACE_PREFIX).trimStart('/')
    }

    private fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    private data class ExternalDocument(
        val uri: Uri,
        val displayName: String,
        val isDirectory: Boolean,
        val size: Long?
    )

    companion object {
        private const val MAX_READ_CHARS = 100_000
        private const val MAX_LIST_ITEMS = 300
        private const val MAX_EXTERNAL_DEPTH = 3
        private const val WORKSPACE_PREFIX = "workspace"
        private const val FILE_ACCESS_PREFS = "document_access"
        private const val KEY_WORKSPACE_TREE_URI = "workspace_tree_uri"

        fun normalizeFormat(format: String?, extension: String): String {
            val value = format?.trim()?.lowercase().orEmpty().ifBlank { extension.lowercase() }
            return when (value) {
                "md", "markdown" -> "md"
                "txt", "text", "" -> "txt"
                "pdf" -> "pdf"
                "docx", "word" -> "docx"
                "ipynb", "notebook" -> "ipynb"
                "c", "cc", "cpp", "cxx", "h", "hpp", "java", "kt", "kts",
                "py", "js", "ts", "tsx", "jsx", "json", "xml", "html", "css", "sh", "sql" -> value
                else -> throw IllegalArgumentException("暂不支持写入格式：$value")
            }
        }

        fun readDocx(file: File): String = file.inputStream().use(::readDocx)

        fun readDocx(input: InputStream): String {
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == "word/document.xml") {
                        val xml = zip.bufferedReader(Charsets.UTF_8).readText()
                        return extractWordText(xml)
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            throw IllegalArgumentException("Word 文件缺少 document.xml")
        }

        fun extractWordText(xml: String): String {
            val result = StringBuilder()
            var insideText = false
            val handler = object : DefaultHandler() {
                override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                    insideText = elementName(localName, qName) == "t"
                }

                override fun characters(ch: CharArray, start: Int, length: Int) {
                    if (insideText) result.append(ch, start, length)
                }

                override fun endElement(uri: String?, localName: String?, qName: String?) {
                    val name = elementName(localName, qName)
                    if (name == "t") insideText = false
                    if (name in setOf("p", "tr")) {
                        if (result.isNotEmpty() && result.last() != '\n') result.append('\n')
                    }
                    if (name == "tc") result.append('\t')
                }

                private fun elementName(localName: String?, qName: String?): String =
                    localName?.takeIf { it.isNotBlank() } ?: qName.orEmpty().substringAfter(':')
            }
            SAXParserFactory.newInstance().apply {
                isNamespaceAware = true
            }.newSAXParser().parse(InputSource(StringReader(xml)), handler)
            return result.toString().trim()
        }

        fun workspaceTreeUri(context: Context): Uri? = context.applicationContext
            .getSharedPreferences(FILE_ACCESS_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_WORKSPACE_TREE_URI, null)
            ?.let(Uri::parse)

        fun setWorkspaceTree(context: Context, uri: Uri): Boolean {
            val appContext = context.applicationContext
            val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            val persisted = runCatching {
                appContext.contentResolver.takePersistableUriPermission(uri, flags)
                true
            }.getOrDefault(false)
            appContext.getSharedPreferences(FILE_ACCESS_PREFS, Context.MODE_PRIVATE).edit().apply {
                if (persisted) putString(KEY_WORKSPACE_TREE_URI, uri.toString())
                else remove(KEY_WORKSPACE_TREE_URI)
            }.apply()
            return persisted
        }

        fun clearWorkspaceTree(context: Context) {
            val appContext = context.applicationContext
            val prefs = appContext.getSharedPreferences(FILE_ACCESS_PREFS, Context.MODE_PRIVATE)
            prefs.getString(KEY_WORKSPACE_TREE_URI, null)?.let { raw ->
                runCatching {
                    appContext.contentResolver.releasePersistableUriPermission(
                        Uri.parse(raw),
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                }
            }
            prefs.edit().remove(KEY_WORKSPACE_TREE_URI).apply()
        }

        fun isWorkspacePath(path: String): Boolean {
            val cleaned = path.trim().replace('\\', '/').trimStart('/')
            return cleaned == WORKSPACE_PREFIX || cleaned.startsWith("$WORKSPACE_PREFIX/")
        }

        fun writeDocx(file: File, content: String) {
            ZipOutputStream(file.outputStream().buffered()).use { zip ->
                zip.writeEntry("[Content_Types].xml", CONTENT_TYPES)
                zip.writeEntry("_rels/.rels", ROOT_RELS)
                val paragraphs = content.lines().joinToString("") { line ->
                    "<w:p><w:r><w:t xml:space=\"preserve\">${escapeXml(line)}</w:t></w:r></w:p>"
                }
                zip.writeEntry("word/document.xml", WORD_DOCUMENT_START + paragraphs + WORD_DOCUMENT_END)
            }
        }

        private fun writePdf(file: File, content: String) {
            val document = PdfDocument()
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 12f
                color = android.graphics.Color.BLACK
            }
            val pageWidth = 595
            val pageHeight = 842
            val margin = 48f
            val lineHeight = 20f
            val maxWidth = pageWidth - margin * 2
            val lines = wrapText(content, paint, maxWidth)
            var pageNumber = 1
            var page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            var y = margin
            lines.forEach { line ->
                if (y + lineHeight > pageHeight - margin) {
                    document.finishPage(page)
                    pageNumber++
                    page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
                    y = margin
                }
                page.canvas.drawText(line, margin, y, paint)
                y += lineHeight
            }
            document.finishPage(page)
            file.outputStream().use(document::writeTo)
            document.close()
        }

        private fun wrapText(content: String, paint: Paint, maxWidth: Float): List<String> {
            val result = mutableListOf<String>()
            content.lines().forEach { sourceLine ->
                if (sourceLine.isEmpty()) {
                    result += ""
                } else {
                    var remaining = sourceLine
                    while (remaining.isNotEmpty()) {
                        val count = paint.breakText(remaining, true, maxWidth, null).coerceAtLeast(1)
                        result += remaining.take(count)
                        remaining = remaining.drop(count)
                    }
                }
            }
            return result.ifEmpty { listOf("") }
        }

        private fun String.withExtensionFor(format: String): String {
            val desired = when (format) { "md" -> "md"; else -> format }
            val current = File(this).extension.lowercase()
            return if (current == desired) this else "$this.$desired"
        }

        private fun escapeXml(value: String): String = value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

        private fun ZipOutputStream.writeEntry(name: String, value: String) {
            putNextEntry(ZipEntry(name))
            write(value.toByteArray(Charsets.UTF_8))
            closeEntry()
        }

        private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>"""
        private const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>"""
        private const val WORD_DOCUMENT_START = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>"""
        private const val WORD_DOCUMENT_END = """<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr></w:body></w:document>"""
    }
}
