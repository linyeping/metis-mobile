package com.mrgreenapps.a11ypilot.agent

import com.mrgreenapps.a11ypilot.BuildConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request

/** Checks the public Metis Mobile release feed without sending model-client headers. */
data class GitHubRelease(
    val tagName: String,
    val name: String,
    val htmlUrl: String,
    val body: String,
    val publishedAt: String?
)

object GitHubReleaseChecker {
    const val repositoryUrl = "https://github.com/linyeping/metis-mobile"
    private const val latestUrl = "https://api.github.com/repos/linyeping/metis-mobile/releases/latest"
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun checkLatest(): Result<GitHubRelease> = runCatching {
        val request = Request.Builder()
            .url(latestUrl)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .get()
            .build()
        val text = ApiRequestExecutor.execute(HttpClients.shared, request, "GitHub 更新检查")
        val root = json.parseToJsonElement(text).jsonObject
        GitHubRelease(
            tagName = root["tag_name"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank {
                error("GitHub 返回的版本号为空")
            },
            name = root["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            htmlUrl = root["html_url"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank {
                repositoryUrl
            },
            body = root["body"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            publishedAt = root["published_at"]?.jsonPrimitive?.contentOrNull
        )
    }

    fun isNewer(remoteTag: String, localVersion: String = BuildConfig.VERSION_NAME): Boolean {
        fun parse(value: String): List<Int> = value
            .removePrefix("v")
            .substringBefore("-")
            .split('.')
            .map { it.toIntOrNull() ?: 0 }
            .let { parts -> (0..2).map { parts.getOrElse(it) { 0 } } }
        return parse(remoteTag).zip(parse(localVersion)).firstOrNull { it.first != it.second }?.let {
            it.first > it.second
        } ?: false
    }
}
