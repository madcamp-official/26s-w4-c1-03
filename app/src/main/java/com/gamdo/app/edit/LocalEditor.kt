package com.gamdo.app.edit

import android.graphics.Bitmap
import kotlin.math.roundToInt

/** Parameters for the non-destructive local edit pass. Values are normalized. */
data class LocalEditParams(
    val brightness: Float = 0f,
    val warmth: Float = 0f,
    val contrast: Float = 0f,
)

enum class LocalFilter(
    val label: String,
    val params: LocalEditParams,
) {
    ORIGINAL("원본", LocalEditParams()),
    MY_STYLE("내 감도", LocalEditParams(brightness = 0.06f, warmth = 0.08f, contrast = 0.04f)),
    CAFE("따뜻한 카페", LocalEditParams(brightness = 0.04f, warmth = 0.16f, contrast = -0.02f)),
    BRIGHT_REVIEW("밝은 리뷰", LocalEditParams(brightness = 0.12f, warmth = 0.02f, contrast = 0.08f)),
    SOFT_FILM("소프트 필름", LocalEditParams(brightness = 0.02f, warmth = 0.1f, contrast = -0.08f)),
    NIGHT_STREET("야간 거리", LocalEditParams(brightness = -0.04f, warmth = -0.08f, contrast = 0.16f)),
}

/**
 * Small deterministic bitmap editor for the offline-first Day 4 flow.
 * The input bitmap is never mutated; callers own the returned bitmap.
 */
object LocalEditor {
    fun apply(source: Bitmap, filter: LocalFilter, adjustments: LocalEditParams): Bitmap {
        val preset = filter.params
        val brightness = (preset.brightness + adjustments.brightness).coerceIn(-1f, 1f)
        val warmth = (preset.warmth + adjustments.warmth).coerceIn(-1f, 1f)
        val contrast = (preset.contrast + adjustments.contrast).coerceIn(-1f, 1f)
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        val contrastFactor = 1f + contrast
        val brightnessOffset = brightness * 255f
        for (index in pixels.indices) {
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
            pixels[index] = (color and -0x1000000) or
                (red.roundToInt().coerceIn(0, 255) shl 16) or
                (green.roundToInt().coerceIn(0, 255) shl 8) or
                blue.roundToInt().coerceIn(0, 255)
        }
        return Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        }
    }
}
