package com.mrgreenapps.a11ypilot

import com.mrgreenapps.a11ypilot.tools.DocumentTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentToolTest {
    @Test
    fun normalizesSupportedDocumentFormats() {
        assertEquals("md", DocumentTool.normalizeFormat("markdown", ""))
        assertEquals("docx", DocumentTool.normalizeFormat("word", ""))
        assertEquals("pdf", DocumentTool.normalizeFormat(null, "pdf"))
        assertEquals("txt", DocumentTool.normalizeFormat(null, ""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedOutputFormat() {
        DocumentTool.normalizeFormat("xlsx", "")
    }

    @Test
    fun preservesParagraphBreaksFromPrefixedWordXml() {
        val xml = """<w:document xmlns:w="word"><w:body><w:p><w:r><w:t>第一段</w:t></w:r></w:p><w:p><w:r><w:t>第二段</w:t></w:r></w:p></w:body></w:document>"""

        assertEquals("第一段\n第二段", DocumentTool.extractWordText(xml))
    }

    @Test
    fun recognizesReadOnlyExternalWorkspacePaths() {
        assertTrue(DocumentTool.isWorkspacePath("workspace"))
        assertTrue(DocumentTool.isWorkspacePath("workspace/project/src/Main.kt"))
        assertTrue(DocumentTool.isWorkspacePath("\\workspace\\notes.md"))
        assertFalse(DocumentTool.isWorkspacePath("project/src/Main.kt"))
        assertFalse(DocumentTool.isWorkspacePath("workspace_evil/file.txt"))
    }
}
