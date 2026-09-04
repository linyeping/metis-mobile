package com.mrgreenapps.a11ypilot.remote

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * 连接地址（中继端点）。
 *
 * 手机端实际连的是中继服务器（桌面端生成配对信息后经中继隧道转发），而非桌面端直连地址。
 * 扫码/配对得到三要素：`{ relay_url, pairing_code, token }`。本类负责把这三种来源归一化成
 * 一个可用的 baseUrl + token：
 *
 *  1. 完整 URL：`https://relay.example.com?pairing_code=ABC&token=XYZ`
 *  2. 自定义协议：`metis://relay.example.com?code=ABC&token=XYZ`（扫码产物常见形式）
 *  3. 裸文本：`relay.example.com ABC XYZ`（用空格/逗号/竖线分隔，作为无扫码时的兜底输入）
 */
data class MetisEndpoint(
    val baseUrl: String,
    val pairingCode: String?,
    val token: String?
) {
    companion object {
        fun parse(raw: String): MetisEndpoint? {
            val input = raw.trim()
            if (input.isEmpty()) return null

            // URL 形态（http/https 或自定义 metis://）
            if (input.startsWith("http://", ignoreCase = true) ||
                input.startsWith("https://", ignoreCase = true) ||
                input.startsWith("metis://", ignoreCase = true)
            ) {
                // 自定义 scheme 归一化成 https（中继一定走 TLS）。
                val normalized = if (input.startsWith("metis://", ignoreCase = true)) {
                    "https://" + input.substringAfter("://")
                } else {
                    input
                }
                return parseUrl(normalized)
            }

            // 裸文本形态：`<中继地址> [配对码] [token]`
            val parts = input.split(Regex("[\\s,|]+")).map(String::trim).filter(String::isNotEmpty)
            if (parts.isEmpty()) return null
            return MetisEndpoint(
                baseUrl = normalizeHost(parts[0]),
                pairingCode = parts.getOrNull(1)?.takeIf(String::isNotBlank),
                token = parts.getOrNull(2)?.takeIf(String::isNotBlank)
            )
        }

        private fun parseUrl(url: String): MetisEndpoint? = runCatching {
            val uri = URI(url)
            val host = uri.host ?: return null
            val scheme = if (uri.scheme.equals("http", ignoreCase = true)) "http" else "https"
            val port = if (uri.port > 0) ":${uri.port}" else ""
            // 中继可能把 Metis 挂在子路径下，保留路径前缀。
            val path = uri.path?.trimEnd('/')?.takeIf(String::isNotBlank).orEmpty()
            val query = parseQuery(uri.rawQuery)
            MetisEndpoint(
                baseUrl = "$scheme://$host$port$path",
                pairingCode = query["pairing_code"] ?: query["code"],
                token = query["token"] ?: query["t"]
            )
        }.getOrNull()

        /** 裸地址补全 https 前缀，同时保留用户显式给出的 http。 */
        private fun normalizeHost(host: String): String {
            val trimmed = host.trim().trimEnd('/')
            return if (trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true)
            ) {
                trimmed
            } else {
                "https://$trimmed"
            }
        }

        private fun parseQuery(rawQuery: String?): Map<String, String> {
            if (rawQuery.isNullOrBlank()) return emptyMap()
            val result = mutableMapOf<String, String>()
            rawQuery.split('&').forEach { pair ->
                val key = pair.substringBefore('=').trim()
                if (key.isEmpty()) return@forEach
                val value = pair.substringAfter('=', "").let { decode(it) }
                result[key] = value
            }
            return result
        }

        private fun decode(value: String): String = runCatching {
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        }.getOrDefault(value)
    }
}
