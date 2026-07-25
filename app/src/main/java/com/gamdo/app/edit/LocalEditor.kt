package com.gamdo.app.edit

import android.graphics.Bitmap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.roundToInt

/** Parameters for the non-destructive local edit pass. Values are normalized. */
data class LocalEditParams(
    val brightness: Float = 0f,
    val warmth: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val fade: Float = 0f,
)

/**
 * The six product presets have a one-to-one local correction entry. MY_STYLE is
 * the user's recommended default; ORIGINAL never alters source pixels.
 */
enum class LocalFilter(
    val label: String,
    val params: LocalEditParams,
) {
    ORIGINAL("원본", LocalEditParams()),
    MY_STYLE("내 감도", LocalEditParams(brightness = 0.06f, warmth = 0.08f, contrast = 0.04f, saturation = 0.03f)),
    CLEAN_SOCIAL("깔끔한 소셜", LocalEditParams(brightness = 0.06f, warmth = 0.04f, contrast = 0.05f, saturation = 0.04f)),
    CANDID_FEED("자연스러운 피드", LocalEditParams(brightness = 0.03f, warmth = 0.02f, contrast = 0.02f, fade = 0.10f)),
    BRIGHT_REVIEW("밝은 리뷰", LocalEditParams(brightness = 0.12f, warmth = 0.02f, contrast = 0.08f, saturation = 0.08f)),
    SOFT_FILM("소프트 필름", LocalEditParams(brightness = 0.02f, warmth = 0.10f, contrast = -0.08f, saturation = -0.05f, fade = 0.20f)),
    CASUAL_PORTRAIT("캐주얼 인물", LocalEditParams(brightness = 0.04f, warmth = 0.04f, contrast = 0.04f, saturation = 0.02f)),
    NIGHT_STREET("밤거리", LocalEditParams(brightness = -0.04f, warmth = -0.08f, contrast = 0.16f, saturation = 0.07f, fade = 0.06f)),
}

/**
 * Deterministic offline editor. The input bitmap is never mutated; callers own
 * the returned bitmap.
 */
object LocalEditor {
    const val PREVIEW_MAX_SIDE = 1024

    /**
     * Creates a display-only copy that fits the largest expected phone image
     * area. Full-resolution rendering is intentionally deferred until save.
     */
    fun previewSource(source: Bitmap, maxSide: Int = PREVIEW_MAX_SIDE): Bitmap {
        val longestSide = maxOf(source.width, source.height)
        if (longestSide <= maxSide) return source
        val scale = maxSide.toFloat() / longestSide
        return Bitmap.createScaledBitmap(
            source,
            (source.width * scale).roundToInt().coerceAtLeast(1),
            (source.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    }

    fun apply(source: Bitmap, filter: LocalFilter, adjustments: LocalEditParams): Bitmap =
        applyInternal(source, filter, adjustments)

    /**
     * Equivalent to [apply], but checks coroutine cancellation between bitmap
     * rows. Rapid filter or slider changes therefore discard stale work.
     */
    suspend fun applyCancellable(source: Bitmap, filter: LocalFilter, adjustments: LocalEditParams): Bitmap =
        currentCoroutineContext().let { coroutineContext ->
            applyInternal(source, filter, adjustments) {
                coroutineContext.ensureActive()
            }
        }

    private fun applyInternal(
        source: Bitmap,
        filter: LocalFilter,
        adjustments: LocalEditParams,
        checkCancelled: (() -> Unit)? = null,
    ): Bitmap {
        val preset = filter.params
        val brightness = (preset.brightness + adjustments.brightness).coerceIn(-1f, 1f)
        val warmth = (preset.warmth + adjustments.warmth).coerceIn(-1f, 1f)
        val contrast = (preset.contrast + adjustments.contrast).coerceIn(-1f, 1f)
        val saturation = (preset.saturation + adjustments.saturation).coerceIn(-1f, 1f)
        val fade = (preset.fade + adjustments.fade).coerceIn(0f, 1f)
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        val contrastFactor = 1f + contrast
        val saturationFactor = 1f + saturation
        val brightnessOffset = brightness * 255f
        for (y in 0 until source.height) {
            checkCancelled?.invoke()
            val rowStart = y * source.width
            for (x in 0 until source.width) {
                val index = rowStart + x
                val color = pixels[index]
                var red = ((color shr 16) and 0xff).toFloat()
                var green = ((color shr 8) and 0xff).toFloat()
                var blue = (color and 0xff).toFloat()
                red += brightnessOffset + warmth * 22f
                green += brightnessOffset + warmth * 5f
                blue += brightnessOffset - warmth * 18f
                red = (red - 128f) * contrastFactor + 128f
                green = (green - 128f) * contrastFactor + 128f
                blue = (blue - 128f) * contrastFactor + 128f
                val luma = red * 0.2126f + green * 0.7152f + blue * 0.0722f
                red = luma + (red - luma) * saturationFactor
                green = luma + (green - luma) * saturationFactor
                blue = luma + (blue - luma) * saturationFactor
                if (fade > 0f) {
                    red = red * (1f - fade) + 242f * fade
                    green = green * (1f - fade) + 236f * fade
                    blue = blue * (1f - fade) + 224f * fade
                }
                pixels[index] = (color and -0x1000000) or
                    (red.roundToInt().coerceIn(0, 255) shl 16) or
                    (green.roundToInt().coerceIn(0, 255) shl 8) or
                    blue.roundToInt().coerceIn(0, 255)
            }
        }
        return Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        }
    }
}
