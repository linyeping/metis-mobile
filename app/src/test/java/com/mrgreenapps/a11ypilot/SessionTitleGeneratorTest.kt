package com.mrgreenapps.a11ypilot

import com.mrgreenapps.a11ypilot.data.SessionTitleGenerator
import com.mrgreenapps.a11ypilot.data.WorkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTitleGeneratorTest {
    @Test
    fun `short first prompt becomes title`() {
        assertEquals("帮我整理课程作业", SessionTitleGenerator.generate("帮我整理课程作业", WorkMode.COWORK))
    }

    @Test
    fun `long title is bounded`() {
        assertTrue(SessionTitleGenerator.generate("请帮我完成一个非常长而且需要很多步骤的安卓开发任务", WorkMode.CODE).length <= 25)
    }
}
