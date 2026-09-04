package com.mrgreenapps.a11ypilot

import com.mrgreenapps.a11ypilot.agent.MetisClientIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetisClientIdentityTest {
    @Test
    fun modelHeadersUseMetisMobileAndAndroid() {
        val headers = MetisClientIdentity.headers()
        val expected = "MetisMobile/${BuildConfig.VERSION_NAME}"
        assertEquals(expected, headers["User-Agent"])
        assertEquals(expected, headers["X-Metis-Client"])
        assertEquals("android", headers["X-Metis-Client-Platform"])
        assertTrue(expected.startsWith("MetisMobile/"))
    }
}
