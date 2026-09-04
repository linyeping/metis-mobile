package com.mrgreenapps.a11ypilot

import com.mrgreenapps.a11ypilot.agent.ApiCallException
import com.mrgreenapps.a11ypilot.agent.RetryPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class RetryPolicyTest {
    @Test
    fun retriesTemporaryNetworkAndProviderFailures() {
        assertTrue(RetryPolicy.shouldRetry(IOException("offline")))
        assertTrue(RetryPolicy.shouldRetry(ApiCallException("busy", statusCode = 503)))
        assertTrue(RetryPolicy.shouldRetry(ApiCallException("rate limited", statusCode = 429)))
    }

    @Test
    fun doesNotRetryCredentialOrRouteFailures() {
        assertFalse(RetryPolicy.shouldRetry(ApiCallException("invalid key", statusCode = 401)))
        assertFalse(RetryPolicy.shouldRetry(ApiCallException("missing route", statusCode = 404)))
        assertFalse(RetryPolicy.shouldRetry(ApiCallException("bad request")))
    }
}
