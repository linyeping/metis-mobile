package com.mrgreenapps.a11ypilot

import com.mrgreenapps.a11ypilot.data.ThinkingState
import com.mrgreenapps.a11ypilot.ui.components.ThinkingOrbEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkingOrbEngineTest {
    @Test
    fun frameIsDeterministicAndDepthSorted() {
        val first = ThinkingOrbEngine.frame(ThinkingState.WORKING, 64f, 1.25f)
        val second = ThinkingOrbEngine.frame(ThinkingState.WORKING, 64f, 1.25f)
        assertEquals(first, second)
        assertTrue(first.dots.zipWithNext().all { (a, b) -> a.z <= b.z })
    }

    @Test
    fun agentStatesUseDistinctGeometry() {
        val signatures = ThinkingState.entries.map { state ->
            val dots = ThinkingOrbEngine.frame(state, 64f, 0.75f).dots
            Triple(dots.size, dots.firstOrNull()?.x, dots.lastOrNull()?.y)
        }
        assertEquals(ThinkingState.entries.size, signatures.distinct().size)
        assertNotEquals(signatures.first(), signatures.last())
    }

    @Test
    fun explicitPresetIsIndependentFromPhysicalCanvasPixels() {
        val compact = ThinkingOrbEngine.frame(ThinkingState.WORKING, 144f, 0.5f, compact = true)
        val full = ThinkingOrbEngine.frame(ThinkingState.WORKING, 144f, 0.5f, compact = false)
        assertTrue(full.dots.size > compact.dots.size)
    }
}
