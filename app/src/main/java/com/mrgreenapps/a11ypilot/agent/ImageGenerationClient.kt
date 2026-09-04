package com.mrgreenapps.a11ypilot.agent

import android.util.Base64
import com.mrgreenapps.a11ypilot.data.ImageBackground
import com.mrgreenapps.a11ypilot.data.ImageCapabilities
import com.mrgreenapps.a11ypilot.data.ImageGenerationSettings
import com.mrgreenapps.a11ypilot.data.ImageQuality
import com.mrgreenapps.a11ypilot.data.ImageStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/** OpenAI-compatible image generation adapter with a conservative capability gate. */
class ImageGenerationClient(
    private val apiKey: String,
    baseUrl: String,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
) {
    private val baseRoot = baseUrl.trimEnd('/').removeSuffix("/v1")
    private val endpoint = "$baseRoot/v1/images/generations"
    private val json = Json { ignoreUnknownKeys = true }

    /** Probe metadata endpoints only; this never spends an image generation request. */
    suspend fun probeCapabilities(): ImageCapabilities = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "尚未设置 GPT API Key" }
        val model = URLEncoder.encode(MODEL, StandardCharsets.UTF_8.name())
        val candidates = listOf(
            "$baseRoot/v1/images/capabilities?model=$model",
            "$baseRoot/v1/models/$model",
            "$baseRoot/v1/models?model=$model"
        )
        for (url in candidates) {
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "application/json")
                .header("User-Agent", MetisClientIdentity.value)
                .header("X-Metis-Client", MetisClientIdentity.value)
                .header("X-Metis-Client-Platform", MetisClientIdentity.PLATFORM)
                .get()
                .build()
            val response = runCatching {
                ApiRequestExecutor.execute(http, request, "GPT 图片能力探针")
            }.getOrNull() ?: continue
            parseCapabilities(response)?.let { return@withContext it }
        }
        ImageCapabilities.conservative()
    }

    suspend fun generate(
        prompt: String,
        outputDirectory: File,
        settings: ImageGenerationSettings = ImageGenerationSettings(),
        capabilities: ImageCapabilities = ImageCapabilities.conservative()
    ): List<File> = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "尚未设置 GPT API Key" }
        require(prompt.isNotBlank()) { "图片描述不能为空" }
        outputDirectory.mkdirs()

        val reference = settings.referenceImagePath?.let(::File)?.takeIf { it.isFile }
        if (reference != null) {
            return@withContext generateEdit(prompt, reference, outputDirectory, settings, capabilities)
        }

        val body = buildRequestBody(prompt, settings, capabilities)
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("User-Agent", MetisClientIdentity.value)
            .header("X-Metis-Client", MetisClientIdentity.value)
            .header("X-Metis-Client-Platform", MetisClientIdentity.PLATFORM)
            .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA))
            .build()
        val responseText = ApiRequestExecutor.execute(http, request, "GPT 图片 API")
        savePayloads(responseText, outputDirectory)
    }

    /** Standard OpenAI image-to-image endpoint. The image is uploaded as multipart binary. */
    private suspend fun generateEdit(
        prompt: String,
        reference: File,
        outputDirectory: File,
        settings: ImageGenerationSettings,
        capabilities: ImageCapabilities
    ): List<File> {
        val generationBody = buildRequestBody(prompt, settings, capabilities)
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", MODEL)
            .addFormDataPart("prompt", generationBody["prompt"]?.jsonPrimitive?.content ?: prompt.trim())
            .addFormDataPart(
                "image",
                reference.name,
                reference.asRequestBody(imageMime(reference).toMediaType())
            )
        generationBody["size"]?.jsonPrimitive?.content?.let { form.addFormDataPart("size", it) }
        generationBody["n"]?.jsonPrimitive?.content?.let { form.addFormDataPart("n", it) }
        generationBody["response_format"]?.jsonPrimitive?.content?.let { form.addFormDataPart("response_format", it) }
        generationBody["quality"]?.jsonPrimitive?.content?.let { form.addFormDataPart("quality", it) }
        generationBody["background"]?.jsonPrimitive?.content?.let { form.addFormDataPart("background", it) }
        generationBody["background_color"]?.jsonPrimitive?.content?.let { form.addFormDataPart("background_color", it) }

        val request = Request.Builder()
            .url("$baseRoot/v1/images/edits")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .header("User-Agent", MetisClientIdentity.value)
            .header("X-Metis-Client", MetisClientIdentity.value)
            .header("X-Metis-Client-Platform", MetisClientIdentity.PLATFORM)
            .post(form.build())
            .build()
        val responseText = try {
            ApiRequestExecutor.execute(http, request, "GPT 图生图 API")
        } catch (error: ApiCallException) {
            if (error.statusCode == 404 || error.statusCode == 405) {
                throw ApiCallException("中转站暂不支持 /v1/images/edits 图生图接口", error, error.statusCode)
            }
            throw error
        }
        return savePayloads(responseText, outputDirectory)
    }

    private fun imageMime(file: File): String = when (file.extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "image/png"
    }

    private suspend fun savePayloads(responseText: String, outputDirectory: File): List<File> =
        parseImagePayloads(responseText).mapIndexed { index, payload ->
            val output = File(outputDirectory, "metis-${System.currentTimeMillis()}-$index.png")
            when {
                payload.base64 != null -> output.writeBytes(Base64.decode(payload.base64, Base64.DEFAULT))
                payload.url != null -> {
                    val download = Request.Builder().url(payload.url).get().build()
                    http.newCall(download).execute().use { response ->
                        if (!response.isSuccessful) throw ApiCallException("图片下载失败：HTTP ${response.code}")
                        val bytes = response.body?.bytes() ?: throw ApiCallException("图片下载为空")
                        output.writeBytes(bytes)
                    }
                }
                else -> throw ApiCallException("GPT 图片 API 未返回图片")
            }
            output
        }

    internal data class ImagePayload(val base64: String? = null, val url: String? = null)

    internal fun parseImagePayload(responseText: String): ImagePayload =
        parseImagePayloads(responseText).firstOrNull()
            ?: throw ApiCallException("GPT 图片 API 未返回 data")

    internal fun parseImagePayloads(responseText: String): List<ImagePayload> {
        val root = json.parseToJsonElement(responseText).jsonObject
        val data = root["data"]?.jsonArray
            ?: throw ApiCallException("GPT 图片 API 未返回 data")
        return data.map { item ->
            val value = item.jsonObject
            ImagePayload(
                base64 = value["b64_json"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                url = value["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            )
        }.filter { it.base64 != null || it.url != null }
    }

    internal fun buildRequestBody(
        prompt: String,
        settings: ImageGenerationSettings,
        capabilities: ImageCapabilities
    ): JsonObject {
        val styleText = settings.style.takeIf { it != ImageStyle.AUTO }?.label
        val negativeText = settings.negativePrompt.trim().takeIf { it.isNotBlank() }
        val requestedSize = capabilities.resolveSize(settings.aspectRatio, settings.resolution)
        val hasServerSize = capabilities.supports("size") && requestedSize != null
        val promptText = buildString {
            append(prompt.trim())
            if (!hasServerSize) {
                append("\n画幅：").append(settings.aspectRatio.label)
                if (settings.resolution != com.mrgreenapps.a11ypilot.data.ImageResolution.AUTO) {
                    append("\n目标分辨率：").append(settings.resolution.label)
                }
            }
            if (styleText != null && capabilities.styleField == null) append("\n风格：").append(styleText)
            if (negativeText != null && capabilities.negativePromptField == null) append("\n避免：").append(negativeText)
        }
        return kotlinx.serialization.json.buildJsonObject {
            put("model", MODEL)
            put("prompt", promptText)
            requestedSize
                ?.takeIf { capabilities.supports("size") }
                ?.let { put("size", it) }
            if (capabilities.supports("n")) {
                put("n", settings.count.coerceIn(1, capabilities.maxCount.coerceIn(1, 4)))
            }
            if (capabilities.supports("response_format")) put("response_format", "b64_json")
            qualityValue(settings.quality, capabilities.qualities)?.let {
                if (capabilities.supports("quality")) put("quality", it)
            }
            backgroundValue(settings.background, capabilities)?.let { put("background", it) }
            if (settings.background == ImageBackground.SOLID &&
                settings.backgroundColor.isNotBlank() && capabilities.supports("background_color")
            ) {
                put("background_color", settings.backgroundColor.trim())
            }
            styleText?.let { value -> capabilities.styleField?.let { put(it, value) } }
            negativeText?.let { value -> capabilities.negativePromptField?.let { put(it, value) } }
            referenceDataUri(settings.referenceImagePath)?.let { dataUri ->
                capabilities.referenceImageField?.let { field ->
                    if (field == "images") putJsonArray(field) { add(dataUri) } else put(field, dataUri)
                }
            }
        }
    }

    private fun qualityValue(requested: ImageQuality, supported: List<String>): String? {
        if (supported.isEmpty()) return null
        val normalized = supported.map { it.lowercase() }
        return when (requested) {
            ImageQuality.AUTO -> normalized.firstOrNull { it == "auto" }
            ImageQuality.STANDARD -> normalized.firstOrNull { it in setOf("standard", "medium", "low") }
            ImageQuality.HIGH -> normalized.firstOrNull { it == "high" }
        }
    }

    private fun backgroundValue(requested: ImageBackground, capabilities: ImageCapabilities): String? {
        if (!capabilities.supportsBackground()) return null
        val supported = capabilities.backgroundValues.map { it.lowercase() }
        return when (requested) {
            ImageBackground.AUTO -> supported.firstOrNull { it == "auto" }
            ImageBackground.TRANSPARENT -> supported.firstOrNull { it == "transparent" }
            ImageBackground.SOLID -> supported.firstOrNull { it in setOf("opaque", "solid") }
        }
    }

    private fun referenceDataUri(path: String?): String? {
        val file = path?.let(::File)?.takeIf { it.isFile } ?: return null
        val mime = when (file.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            else -> "image/png"
        }
        return "data:$mime;base64,${Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)}"
    }

    internal fun parseCapabilities(responseText: String): ImageCapabilities? = runCatching {
        val root = json.parseToJsonElement(responseText).jsonObject
        val candidate = root["capabilities"]?.jsonObject
            ?: root["data"]?.let { element ->
                when (element) {
                    is JsonObject -> element
                    is JsonArray -> element.firstOrNull()?.let { it as? JsonObject }
                    else -> null
                }
            }
            ?: root
        val nested = candidate["image_generation"]?.jsonObject
            ?: candidate["imageGeneration"]?.jsonObject
            ?: candidate["metadata"]?.jsonObject
            ?: candidate
        val sizes = stringList(nested, "sizes", "supported_sizes", "supportedSizes")
        val qualities = stringList(nested, "qualities", "supported_qualities", "supportedQualities")
        val fields = stringList(nested, "fields", "supported_fields", "supportedFields", "supported_parameters")
            .map(::normalizeField)
            .toSet()
        val backgrounds = stringList(nested, "backgrounds", "background_values", "backgroundValues")
        val maxCount = nested["max_n"]?.jsonPrimitive?.intOrNull
            ?: nested["maxCount"]?.jsonPrimitive?.intOrNull
            ?: nested["max_count"]?.jsonPrimitive?.intOrNull
        val explicit = sizes.isNotEmpty() || qualities.isNotEmpty() || fields.isNotEmpty() ||
            backgrounds.isNotEmpty() || maxCount != null
        if (!explicit) return@runCatching null

        val allFields = fields.toMutableSet().apply {
            if (sizes.isNotEmpty()) add("size")
            if (qualities.isNotEmpty()) add("quality")
            if (maxCount != null) add("n")
            if (backgrounds.isNotEmpty()) add("background")
        }
        val reference = fields.firstOrNull { it in setOf("image", "images", "reference_image") }
        val negative = fields.firstOrNull { it == "negative_prompt" }
        val style = fields.firstOrNull { it == "style" }
        ImageCapabilities(
            sizes = sizes.ifEmpty { ImageCapabilities.conservative().sizes },
            qualities = qualities,
            maxCount = (maxCount ?: 1).coerceIn(1, 4),
            // Do not infer fields that the server did not advertise. The conservative
            // fallback below is the only place where response_format is known-safe.
            fields = allFields,
            backgroundValues = backgrounds,
            referenceImageField = reference,
            negativePromptField = negative,
            styleField = style
        )
    }.getOrNull()

    private fun stringList(root: JsonObject, vararg keys: String): List<String> {
        val value = keys.asSequence().mapNotNull { root[it] }.firstOrNull() ?: return emptyList()
        return when (value) {
            is JsonArray -> value.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotBlank) }
            else -> value.jsonPrimitive.contentOrNull
                ?.split(',', ' ', '\n')
                ?.map(String::trim)
                ?.filter(String::isNotBlank)
                .orEmpty()
        }
    }

    private fun normalizeField(value: String): String = value.trim()
        .replace(Regex("([a-z])([A-Z])"), "$1_$2")
        .replace('-', '_')
        .lowercase()

    companion object {
        const val MODEL = "gpt-image-2"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
