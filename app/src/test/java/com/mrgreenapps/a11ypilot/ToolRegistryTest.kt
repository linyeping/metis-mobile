package com.mrgreenapps.a11ypilot

import com.mrgreenapps.a11ypilot.data.ThinkingState
import com.mrgreenapps.a11ypilot.data.WorkMode
import com.mrgreenapps.a11ypilot.tools.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRegistryTest {
    @Test
    fun unifiedChatExposesPhoneFilesAndTermuxTools() {
        val tools = ToolRegistry.getToolsForMode(WorkMode.CHAT)
        assertTrue("run_command" in tools)
        assertTrue("tap" in tools)
        assertTrue("write_file" in tools)
        assertTrue("web_search" in tools)
    }

    @Test
    fun coworkExposesDocumentsAndPhoneUse() {
        val tools = ToolRegistry.getToolsForMode(WorkMode.COWORK)
        assertTrue("write_file" in tools)
        assertTrue("set_alarm" in tools)
        assertTrue("list_alarms" in tools)
        assertTrue("cancel_all_alarms" in tools)
        assertTrue("open_bilibili_search" in tools)
        assertTrue("tap" in tools)
        // Unified toolkit: COWORK mode also gets run_command in the unified Metis workspace.
        assertTrue("run_command" in tools)
    }

    @Test
    fun codeExposesFilesAndTermuxOnly() {
        val tools = ToolRegistry.getToolsForMode(WorkMode.CODE)
        assertTrue("read_file" in tools)
        assertTrue("write_file" in tools)
        assertTrue("run_command" in tools)
        assertFalse("tap" in tools)
        assertFalse("set_alarm" in tools)
    }

    @Test
    fun thinkingStateFollowsExecutedTool() {
        assertEquals(ThinkingState.RETRIEVING, ToolRegistry.thinkingStateFor(WorkMode.COWORK, "read_file"))
        assertEquals(ThinkingState.EXECUTING, ToolRegistry.thinkingStateFor(WorkMode.COWORK, "set_alarm"))
        assertEquals(ThinkingState.ORGANIZING, ToolRegistry.thinkingStateFor(WorkMode.CODE, "done"))
    }
}
