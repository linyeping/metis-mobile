package com.mrgreenapps.a11ypilot.agent

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Process-wide shared OkHttpClient.
 *
 * Before this, every API turn constructed a fresh LLM client, and every client built its own
 * OkHttpClient — each with an independent connection pool and dispatcher thread pool. A single
 * 25-step run leaked 25+ idle executors. Sharing one client reuses sockets and threads across
 * turns and across providers.
 */
object HttpClients {
    val shared: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
