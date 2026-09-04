package com.mrgreenapps.a11ypilot.agent

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.UUID

/**
 * Parses SillyTavern character cards (the same format NativeTavern consumes) into
 * [CharacterCard] instances for use as Metis personas.
 *
 * Supports:
 *  - PNG cards: character JSON is embedded in PNG `tEXt` chunks under keyword
 *    `ccv3` (V3, base64 of a `{spec, data}` envelope) or `chara` (V2, base64 of the data).
 *  - JSON cards: a raw V2/V3 character JSON document.
 *
 * The SillyTavern `extensions` object is free-form; the optional `extensions.phoneuse`
 * sub-object is read to seed [CharacterCard.allowPhoneUse] and [CharacterCard.allowedTools]:
 *
 *     "extensions": { "phoneuse": { "enabled": true, "allowed_tools": ["发微信", "查快递"] } }
 */
object CharacterCardParser {

    private const val TAG = "CharacterCardParser"

    sealed interface Result {
        data class Success(val card: CharacterCard) : Result
        data class Error(val message: String) : Result
    }

    /** Parse a PNG byte stream that may carry embedded card metadata. */
    fun fromPng(bytes: ByteArray): Result {
        val pngText = try {
            extractPngTextChunks(bytes)
        } catch (t: Throwable) {
            return Result.Error("PNG 解析失败：${t.message ?: "未知错误"}")
        }

        // Prefer V3 (ccv3) over V2 (chara)
        val base64 = pngText["ccv3"] ?: pngText["chara"]
            ?: return Result.Error("该 PNG 不含角色卡数据（缺少 ccv3/chara 元数据）")

        val jsonStr = try {
            String(Base64.getDecoder().decode(base64.trim()), Charsets.UTF_8)
        } catch (t: Throwable) {
            return Result.Error("角色卡 base64 解码失败：${t.message ?: "未知错误"}")
        }

        return fromJson(jsonStr, source = "tavern")
    }

    /** Parse a raw JSON character card document (V2 data or V3 envelope). */
    fun fromJson(jsonStr: String, source: String = "tavern"): Result {
        return try {
            val root = JSONObject(jsonStr)
            // V3 wraps data under "data"; V2 is the data object directly.
            val data = if (root.has("spec") && root.has("data")) root.getJSONObject("data") else root
            Result.Success(buildCard(data, source, jsonStr))
        } catch (t: Throwable) {
            Result.Error("角色卡 JSON 解析失败：${t.message ?: "未知错误"}")
        }
    }

    /** Auto-detect: PNG magic bytes → PNG path, otherwise JSON. */
    fun fromBytes(bytes: ByteArray): Result {
        val isPng = bytes.size >= 8 &&
            (bytes[0].toInt() and 0xFF) == 0x89 && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()
        return if (isPng) fromPng(bytes) else fromJson(String(bytes, Charsets.UTF_8))
    }

    private fun buildCard(data: JSONObject, source: String, rawJson: String): CharacterCard {
        val name = data.optString("name").trim().ifBlank { "未命名角色" }

        // Description is the richest persona source: combine description + personality + scenario.
        val description = buildString {
            val desc = data.optString("description").trim()
            if (desc.isNotEmpty()) append(desc)
            val personality = data.optString("personality").trim()
            if (personality.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("性格：").append(personality)
            }
            val scenario = data.optString("scenario").trim()
            if (scenario.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("场景：").append(scenario)
            }
            val systemPrompt = data.optString("system_prompt").trim()
            if (systemPrompt.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append(systemPrompt)
            }
        }.trim().ifBlank { name }

        // Read the optional phoneuse extension.
        val phoneuse = data.optJSONObject("extensions")?.optJSONObject("phoneuse")
        val allowPhoneUse = phoneuse?.optBoolean("enabled", false) ?: false
        val allowedTools = phoneuse?.optJSONArray("allowed_tools")?.let { arr ->
            buildList {
                for (i in 0 until arr.length()) {
                    val tool = arr.optString(i).trim()
                    if (tool.isNotBlank()) add(tool)
                }
            }
        } ?: emptyList()

        return CharacterCard(
            id = UUID.randomUUID().toString(),
            name = name,
            description = description,
            allowPhoneUse = allowPhoneUse,
            allowedTools = allowedTools,
            source = source,
            rawJson = rawJson
        )
    }

    /**
     * Minimal PNG `tEXt`/`iTXt` chunk reader. We only need the text keywords, so we walk
     * the chunk chain directly instead of pulling in a PNG library.
     */
    private fun extractPngTextChunks(bytes: ByteArray): Map<String, String> {
        require(bytes.size >= 8) { "不是有效的 PNG 文件" }
        val sig = bytes.copyOfRange(0, 8)
        require(sig.contentEquals(PNG_SIGNATURE)) { "PNG 签名错误" }

        val text = HashMap<String, String>()
        var offset = 8
        while (offset + 12 <= bytes.size) {
            val length = readIntBE(bytes, offset)
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            val dataStart = offset + 8
            val dataEnd = dataStart + length
            if (dataEnd > bytes.size) break

            when (type) {
                "tEXt" -> {
                    // keyword \0 value (both Latin-1)
                    val sep = findNull(bytes, dataStart, dataEnd)
                    if (sep in dataStart until dataEnd) {
                        val keyword = String(bytes, dataStart, sep - dataStart, Charsets.ISO_8859_1).lowercase()
                        val value = String(bytes, sep + 1, dataEnd - sep - 1, Charsets.ISO_8859_1)
                        text[keyword] = value
                    }
                }
                "IEND" -> break
            }
            offset = dataEnd + 4 // skip CRC
        }
        return text
    }

    private fun findNull(bytes: ByteArray, from: Int, to: Int): Int {
        for (i in from until to) if (bytes[i] == 0.toByte()) return i
        return -1
    }

    private fun readIntBE(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
        0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte()
    )
}
