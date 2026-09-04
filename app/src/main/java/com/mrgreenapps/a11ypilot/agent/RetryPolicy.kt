package com.mrgreenapps.a11ypilot.agent

import java.io.IOException
import java.net.SocketTimeoutException

/** Shared retry rules for temporary provider or network failures. */
object RetryPolicy {
    const val MAX_RETRY_ATTEMPTS = 10
    const val RETRY_INTERVAL_MILLIS = 20_000L

    fun shouldRetry(error: Throwable): Boolean {
        if (error is SocketTimeoutException || error is IOException) return true
        val apiError = error as? ApiCallException ?: return false
        return when (apiError.statusCode) {
            null -> apiError.retryable
            408, 425, 429, 500, 502, 503, 504 -> true
            else -> false
        }
    }
}
