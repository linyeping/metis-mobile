package com.mrgreenapps.a11ypilot.agent

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class NetworkDiagnosticResult(
    val transport: String,
    val vpnActive: Boolean,
    val systemProxy: String,
    val baseUrl: String,
    val connectivity: String,
    val validated: Boolean = false,
    val dnsServers: String = "未读取",
    val networkSummary: String = ""
)

object NetworkDiagnostics {
    suspend fun inspect(context: Context, baseUrl: String, apiKey: String = ""): NetworkDiagnosticResult =
        withContext(Dispatchers.IO) {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val active = manager?.activeNetwork
            val capabilities = active?.let(manager::getNetworkCapabilities)
            val allNetworks = manager?.allNetworks.orEmpty()
            val vpnActive = allNetworks.any { network ->
                manager?.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            } == true
            val transport = when {
                capabilities == null -> "无活动网络"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动数据"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
                else -> "其他网络"
            }
            val proxy = active?.let(manager::getLinkProperties)?.httpProxy
            val linkProperties = active?.let(manager::getLinkProperties)
            val dns = linkProperties?.dnsServers.orEmpty()
                .joinToString(", ") { it.hostAddress.orEmpty() }
                .ifBlank { "未读取" }
            val networkSummary = allNetworks.joinToString(" | ") { network ->
                val caps = manager?.getNetworkCapabilities(network)
                val transports = listOf(
                    NetworkCapabilities.TRANSPORT_VPN to "VPN",
                    NetworkCapabilities.TRANSPORT_WIFI to "Wi-Fi",
                    NetworkCapabilities.TRANSPORT_CELLULAR to "移动数据",
                    NetworkCapabilities.TRANSPORT_ETHERNET to "以太网"
                ).filter { caps?.hasTransport(it.first) == true }.joinToString("+") { it.second }
                    .ifBlank { "其他" }
                val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                "$transports${if (validated) "(已验证)" else "(未验证)"}"
            }.ifBlank { "无可用网络" }
            NetworkDiagnosticResult(
                transport = transport,
                vpnActive = vpnActive,
                systemProxy = proxy?.let { "${it.host}:${it.port}" } ?: "未设置",
                baseUrl = baseUrl.trim(),
                connectivity = probe(baseUrl, apiKey),
                validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
                dnsServers = dns,
                networkSummary = networkSummary
            )
        }

    private fun probe(baseUrl: String, apiKey: String): String {
        val url = baseUrl.trim().ifBlank { return "Base URL 为空" }
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(22, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
        val root = url.trimEnd('/').removeSuffix("/v1")
        val checks = listOf(
            "入口" to root,
            "模型" to "$root/v1/models"
        )
        val results = checks.map { (label, target) ->
            val started = System.nanoTime()
            runCatching {
                val request = Request.Builder().url(target).get()
                    .header("Accept", "application/json")
                    .apply { if (apiKey.isNotBlank() && label == "模型") header("Authorization", "Bearer $apiKey") }
                    .build()
                client.newCall(request).execute().use { response ->
                    val elapsed = (System.nanoTime() - started) / 1_000_000
                    "$label HTTP ${response.code} ${elapsed}ms"
                }
            }.getOrElse { error ->
                "$label 失败：${ApiErrorMessage.fromThrowable(error)}"
            }
        }
        return results.joinToString("；")
    }
}
