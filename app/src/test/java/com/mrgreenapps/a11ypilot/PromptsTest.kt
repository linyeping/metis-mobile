package com.mrgreenapps.a11ypilot

import com.mrgreenapps.a11ypilot.agent.Prompts
import com.mrgreenapps.a11ypilot.data.WorkMode
import com.mrgreenapps.a11ypilot.tools.ToolRegistry
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptsTest {
    @Test
    fun schemasMatchRegistryPerMode() {
        val chat = toolNames(WorkMode.CHAT)
        val cowork = toolNames(WorkMode.COWORK)
        val code = toolNames(WorkMode.CODE)

        // CHAT and COWORK expose the unified Metis toolkit; CODE is narrowed to file/git/run_command.
        assertEquals(ToolRegistry.getToolsForMode(WorkMode.CHAT), chat)
        assertEquals(ToolRegistry.getToolsForMode(WorkMode.COWORK), cowork)
        assertEquals(ToolRegistry.getToolsForMode(WorkMode.CODE), code)
        assertTrue("write_file" in cowork)
        assertTrue("open_bilibili_search" in cowork)
        assertTrue("run_command" in code)
        assertFalse("launch_app" in code)
    }

    @Test
    fun eachModeHasSpecificExecutionAndVerificationRules() {
        val chat = Prompts.systemForMode(WorkMode.CHAT)
        val cowork = Prompts.systemForMode(WorkMode.COWORK)
        val code = Prompts.systemForMode(WorkMode.CODE)

        assertTrue(chat.contains("UNIFIED METIS WORKSPACE"))
        assertTrue(chat.contains("run_command"))
        assertTrue(cowork.contains("COWORK MODE"))
        assertTrue(cowork.contains("dump_screen"))
        assertTrue(cowork.contains("verify it through read_file or list_files"))
        assertTrue(code.contains("CODE MODE"))
        assertTrue(code.contains("inspect before editing"))
        assertTrue(code.contains("Do not claim a test passed"))
    }

    @Test
    fun customPersonaIsAppendedWithoutReplacingModeRules() {
        val prompt = Prompts.systemForMode(WorkMode.CHAT, "回答要简洁直接，先给结论。")

        assertTrue(prompt.contains("PERSONA"))
        assertTrue(prompt.contains("回答要简洁直接，先给结论。"))
        assertTrue(prompt.contains("UNIFIED METIS WORKSPACE"))
    }

    @Test
    fun shortFollowUpsStayBoundToTheSessionObjective() {
        val prompt = Prompts.systemForMode(WorkMode.CODE)

        assertTrue(prompt.contains("你写吧"))
        assertTrue(prompt.contains("最近一个未完成目标"))
    }

    @Test
    fun strictFunctionSchemasRequireEveryDeclaredProperty() {
        listOf(WorkMode.CHAT, WorkMode.COWORK, WorkMode.CODE).forEach { mode ->
            Prompts.anthropicTools(mode).forEach { tool ->
                val schema = tool.jsonObject.getValue("input_schema").jsonObject
                val properties = schema.getValue("properties").jsonObject.keys
                val required = schema["required"]?.jsonArray
                    ?.map { it.jsonPrimitive.content }
                    .orEmpty()
                    .toSet()
                assertEquals("strict schema for ${tool.jsonObject.getValue("name")}", properties, required)
                assertFalse(schema.getValue("additionalProperties").jsonPrimitive.content.toBoolean())
            }
        }
    }

    private fun toolNames(mode: WorkMode): Set<String> = Prompts.anthropicTools(mode)
        .map { it.jsonObject.getValue("name").jsonPrimitive.content }
        .toSet()
}
