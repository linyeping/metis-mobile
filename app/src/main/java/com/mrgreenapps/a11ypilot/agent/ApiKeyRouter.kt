package com.mrgreenapps.a11ypilot.agent

import android.content.Context
import com.mrgreenapps.a11ypilot.EventLog
import com.mrgreenapps.a11ypilot.data.ApiKeyProfile
import com.mrgreenapps.a11ypilot.data.ApiKeyProfileProvider
import com.mrgreenapps.a11ypilot.data.ApiKeyRepository
import com.mrgreenapps.a11ypilot.data.ModelProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Routes one logical model request through all compatible configured keys.
 *
 * The router deliberately lives below the UI and above the HTTP clients. A failed key is
 * cooled down, the next request starts at a different key, and deterministic request errors
 * (bad model, malformed payload, and missing route) are returned immediately.
 */
object ApiKeyRouter {
    enum class Strategy { ROUND_ROBIN, FAILOVER, HEALTH_FIRST }

    data class Status(
        val id: String,
        val label: String,
        val failures: Int,
        val cooldownRemainingMs: Long,
        val lastError: String = ""
    ) {
        val available: Boolean get() = cooldownRemainingMs <= 0L
    }

    private data class Health(
        var failures: Int = 0,
        var cooldownUntil: Long = 0L,
        var lastError: String = ""
    )

    private val health = ConcurrentHashMap<String, Health>()
    private val pointers = ConcurrentHashMap<String, AtomicInteger>()

    suspend fun <T> complete(
        context: Context,
        provider: ModelProvider,
        model: String,
        block: suspend (apiKey: String, baseUrl: String, profileId: String) -> T
    ): T {
        ApiKeyRepository.ensureSeeded(context)
        val candidates = candidates(context, provider)
        if (candidates.isEmpty()) {
            throw ApiCallException("尚未配置可用于 ${provider.displayName} 的 API Key，请先打开设置。")
        }

        val strategy = AgentSettings.apiKeyRotationStrategy(context).first()
        val ordered = order(candidates, provider, model, strategy)
        val failures = mutableListOf<Pair<ApiKeyProfile, Throwable>>()
        ordered.forEachIndexed { index, profile ->
            val state = health.computeIfAbsent(profile.id) { Health() }
            if (state.cooldownUntil > System.currentTimeMillis() && ordered.size > 1) {
                EventLog.append("api-router> skip cooling profile=${profile.label}")
                return@forEachIndexed
            }
            EventLog.append("api-router> request profile=${profile.label} (${index + 1}/${ordered.size})")
            try {
                val result = block(profile.apiKey, profile.baseUrl, profile.id)
                markSuccess(profile.id)
                advance(provider, model, strategy)
                return result
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (!shouldRotate(error)) throw error
                markFailure(profile.id, error)
                failures += profile to error
                val code = (error as? ApiCallException)?.statusCode?.toString() ?: "网络"
                EventLog.append("api-router> rotate profile=${profile.label} reason=$code")
            }
        }

        val detail = failures.joinToString("；") { (profile, error) ->
            val code = (error as? ApiCallException)?.statusCode?.let { "HTTP $it" } ?: "网络"
            "${profile.label}: $code"
        }
        val last = failures.lastOrNull()?.second as? ApiCallException
        throw ApiCallException(
            message = "多密钥轮询失败：已尝试 ${failures.size} 个密钥。${detail.ifBlank { "所有密钥当前都在冷却中" }}",
            cause = last,
            statusCode = last?.statusCode,
            retryable = true
        )
    }

    suspend fun status(context: Context, provider: ModelProvider): List<Status> {
        ApiKeyRepository.ensureSeeded(context)
        val now = System.currentTimeMillis()
        return candidates(context, provider).map { profile ->
            val state = health[profile.id]
            Status(
                id = profile.id,
                label = profile.label,
                failures = state?.failures ?: 0,
                cooldownRemainingMs = ((state?.cooldownUntil ?: 0L) - now).coerceAtLeast(0L),
                lastError = state?.lastError.orEmpty()
            )
        }
    }

    fun reset() {
        health.clear()
        pointers.clear()
    }

    private suspend fun candidates(context: Context, provider: ModelProvider): List<ApiKeyProfile> {
        val profiles = ApiKeyRepository.observeProfiles(context).first()
            .filter { it.apiKey.isNotBlank() }
            .filter { profile ->
                when (provider) {
                    ModelProvider.DEEPSEEK -> profile.provider == ApiKeyProfileProvider.DEEPSEEK ||
                        (profile.provider == ApiKeyProfileProvider.CUSTOM && profile.baseUrl.contains("deepseek.com", ignoreCase = true))
                    ModelProvider.CUSTOM_OPENAI, ModelProvider.CUSTOM_CLAUDE ->
                        profile.provider == ApiKeyProfileProvider.RELAY ||
                            (profile.provider == ApiKeyProfileProvider.CUSTOM && !profile.baseUrl.contains("deepseek.com", ignoreCase = true))
                }
            }
        // A duplicate profile should not consume a rotation attempt or cause duplicate traffic.
        return profiles.distinctBy { "${it.apiKey}\u0000${it.baseUrl.trimEnd('/')}" }
    }

    private fun order(
        profiles: List<ApiKeyProfile>,
        provider: ModelProvider,
        model: String,
        strategy: Strategy
    ): List<ApiKeyProfile> {
        if (profiles.size < 2) return profiles
        val key = "${provider.name}:$model"
        val pointer = pointers.computeIfAbsent(key) { AtomicInteger(0) }
        return when (strategy) {
            Strategy.FAILOVER -> profiles
            Strategy.HEALTH_FIRST -> profiles.sortedWith(compareBy<ApiKeyProfile> {
                val state = health[it.id]
                if ((state?.cooldownUntil ?: 0L) > System.currentTimeMillis()) 1 else 0
            }.thenBy { health[it.id]?.failures ?: 0 }.thenBy { profiles.indexOf(it) })
            Strategy.ROUND_ROBIN -> {
                val start = Math.floorMod(pointer.get(), profiles.size)
                profiles.drop(start) + profiles.take(start)
            }
        }
    }

    private fun advance(provider: ModelProvider, model: String, strategy: Strategy) {
        if (strategy == Strategy.ROUND_ROBIN) {
            pointers["${provider.name}:$model"]?.let { pointer ->
                if (pointer.get() < Int.MAX_VALUE - 1) pointer.incrementAndGet()
            }
        }
    }

    internal fun shouldRotate(error: Throwable): Boolean {
        val apiError = error as? ApiCallException ?: return error is IOException
        return apiError.retryable || apiError.statusCode in setOf(401, 403, 429, 500, 502, 503, 504)
    }

    private fun markSuccess(id: String) {
        health.computeIfAbsent(id) { Health() }.let {
            synchronized(it) {
                it.failures = 0
                it.cooldownUntil = 0L
                it.lastError = ""
            }
        }
    }

    private fun markFailure(id: String, error: Throwable) {
        val state = health.computeIfAbsent(id) { Health() }
        synchronized(state) {
            state.failures = (state.failures + 1).coerceAtMost(10)
            state.lastError = (error.message ?: "请求失败").take(160)
            val code = (error as? ApiCallException)?.statusCode
            val cooldown = when (code) {
                401, 403 -> 10 * 60 * 1000L
                429 -> 60 * 1000L
                500, 502, 503, 504 -> 30 * 1000L
                else -> 15 * 1000L
            }
            state.cooldownUntil = System.currentTimeMillis() + cooldown
        }
    }
}
