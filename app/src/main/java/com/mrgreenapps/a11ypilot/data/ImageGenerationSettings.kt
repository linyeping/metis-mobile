package com.mrgreenapps.a11ypilot.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.abs

private val Context.imageGenerationDataStore by preferencesDataStore(name = "image_generation_settings")

@Serializable
enum class ImageAspectRatio(val label: String, val numericRatio: Float) {
    // Keep the existing enum names for DataStore compatibility; only the visible catalog expands.
    LANDSCAPE("16:9", 16f / 9f),
    WIDE("4:3", 4f / 3f),
    SQUARE("1:1", 1f),
    PORTRAIT("3:4", 3f / 4f),
    TALL("9:16", 9f / 16f)
}

@Serializable
enum class ImageResolution(val label: String, val targetPixels: Int?) {
    AUTO("自动", null),
    ONE_K("1K", 1024),
    TWO_K("2K", 2048),
    FOUR_K("4K", 4096)
}

@Serializable
enum class ImageQuality(val label: String) {
    AUTO("自动"),
    STANDARD("标准"),
    HIGH("高质量")
}

@Serializable
enum class ImageBackground(val label: String) {
    AUTO("自动"),
    TRANSPARENT("透明"),
    SOLID("纯色")
}

@Serializable
enum class ImageStyle(val label: String) {
    AUTO("自动"),
    REALISTIC("写实"),
    ILLUSTRATION("插画"),
    PHOTOGRAPHY("摄影"),
    THREE_D("3D"),
    DESIGN("设计稿")
}

@Serializable
data class ImageGenerationSettings(
    val aspectRatio: ImageAspectRatio = ImageAspectRatio.SQUARE,
    val resolution: ImageResolution = ImageResolution.ONE_K,
    val quality: ImageQuality = ImageQuality.AUTO,
    val count: Int = 1,
    val background: ImageBackground = ImageBackground.AUTO,
    val backgroundColor: String = "",
    val style: ImageStyle = ImageStyle.AUTO,
    val referenceImagePath: String? = null,
    val negativePrompt: String = ""
) {
    fun shortLabel(capabilities: ImageCapabilities = ImageCapabilities.conservative()): String {
        return "${aspectRatio.label} · ${resolution.label} · ${count.coerceIn(1, 4)}张"
    }
}

/** Fields are populated only from a capability response. The conservative value is the
 * currently verified request shape and intentionally exposes no speculative options. */
data class ImageCapabilities(
    val sizes: List<String> = listOf("1024x1024"),
    val qualities: List<String> = emptyList(),
    val maxCount: Int = 4,
    val fields: Set<String> = setOf("size", "n", "response_format"),
    val backgroundValues: List<String> = emptyList(),
    val referenceImageField: String? = null,
    val negativePromptField: String? = null,
    val styleField: String? = null
) {
    fun supports(field: String): Boolean = field in fields
    fun supportsAspect(aspect: ImageAspectRatio): Boolean = sizes.any { it.matchesAspect(aspect) }
    fun supportsResolution(aspect: ImageAspectRatio, resolution: ImageResolution): Boolean =
        resolveSize(aspect, resolution) != null

    fun resolveSize(aspect: ImageAspectRatio, resolution: ImageResolution): String? {
        val candidates = sizes.filter { it.matchesAspect(aspect) }
        if (candidates.isEmpty()) return null
        if (resolution == ImageResolution.AUTO) {
            return candidates.minByOrNull { it.maxDimension() }
        }
        val target = resolution.targetPixels ?: return candidates.first()
        // Do not silently downgrade an explicit 2K/4K selection to a 1K asset.
        return candidates.firstOrNull { it.maxDimension() == target }
    }

    fun resolveResolution(aspect: ImageAspectRatio, requested: ImageResolution): ImageResolution {
        if (requested != ImageResolution.AUTO && supportsResolution(aspect, requested)) return requested
        val size = resolveSize(aspect, requested) ?: return ImageResolution.AUTO
        val dimension = size.maxDimension()
        return when {
            dimension >= 3072 -> ImageResolution.FOUR_K
            dimension >= 1536 -> ImageResolution.TWO_K
            else -> ImageResolution.ONE_K
        }
    }

    fun supportsBackground(): Boolean = supports("background") && backgroundValues.isNotEmpty()

    companion object {
        fun conservative() = ImageCapabilities()
    }
}

private fun String.parsedDimensions(): Pair<Int, Int>? {
    val parts = trim().lowercase().split('x')
    if (parts.size != 2) return null
    val width = parts[0].toIntOrNull() ?: return null
    val height = parts[1].toIntOrNull() ?: return null
    return width to height
}

private fun String.matchesAspect(aspect: ImageAspectRatio): Boolean {
    val (width, height) = parsedDimensions() ?: return false
    return abs(width.toFloat() / height.toFloat() - aspect.numericRatio) <= 0.08f
}

private fun String.maxDimension(): Int = parsedDimensions()?.let { maxOf(it.first, it.second) } ?: Int.MAX_VALUE

class ImageGenerationSettingsRepository(private val context: Context) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }

    fun observe(sessionId: String): Flow<ImageGenerationSettings> {
        val key = stringPreferencesKey("session_${sessionId}_settings")
        return context.imageGenerationDataStore.data
            .map { preferences -> decode(preferences[key]) }
            .catch { emit(ImageGenerationSettings()) }
    }

    suspend fun get(sessionId: String): ImageGenerationSettings = observe(sessionId).first()

    suspend fun save(sessionId: String, settings: ImageGenerationSettings) {
        val key = stringPreferencesKey("session_${sessionId}_settings")
        context.imageGenerationDataStore.edit { preferences ->
            preferences[key] = json.encodeToString(settings.copy(count = settings.count.coerceIn(1, 4)))
        }
    }

    suspend fun clear(sessionId: String) {
        val key = stringPreferencesKey("session_${sessionId}_settings")
        context.imageGenerationDataStore.edit { it.remove(key) }
    }

    private fun decode(value: String?): ImageGenerationSettings =
        value?.let { runCatching { json.decodeFromString<ImageGenerationSettings>(it) }.getOrNull() }
            ?: ImageGenerationSettings()
}
