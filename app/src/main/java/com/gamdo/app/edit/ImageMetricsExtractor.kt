package com.gamdo.app.edit

import android.graphics.Bitmap
import com.gamdo.app.camera.scaledToMaxSide
import com.gamdo.app.detect.ImageMetrics

/**
 * Bitmap → [ImageMetrics] adapter — the missing producer for 담당 B's
 * `ProblemDiagnoser` (P2_Plan §0.5: "the module never receives a Bitmap; A extracts
 * ImageMetrics and passes it in").
 *
 * **This file is deliberately thin.** Every number is computed by the pure
 * functions in `ImageStats.kt`; all this layer does is get pixels out of a Bitmap.
 * The module has no `androidTest` source set and no Robolectric, so anything
 * written here is unverifiable on CI — keep it at "unpack and delegate".
 *
 * It lives in `edit/` rather than `detect/` on purpose: `detect/` stays 담당 B's
 * pure-Kotlin territory. Team agreement — do not relocate.
 */
object ImageMetricsExtractor {

    /**
     * Analysis resolution. Blur and exposure statistics are scale-sensitive, so the
     * value must stay fixed once `DiagnoserConfig.blurVariance` is tuned against it
     * — changing it silently re-tunes every blur threshold.
     */
    const val ANALYSIS_MAX_SIDE = 512

    /**
     * @param tiltDeg capture-time sensor reading; a gallery import has none and passes 0.
     * @param subject normalized subject box from ML Kit, or null when nothing was detected.
     */
    fun extract(
        bitmap: Bitmap,
        tiltDeg: Float = 0f,
        subject: SubjectBox? = null,
        maxSide: Int = ANALYSIS_MAX_SIDE,
    ): ImageMetrics = bitmap.withAnalysisPixels(maxSide) { pixels, width, height ->
        computeImageMetrics(
            pixels = pixels,
            width = width,
            height = height,
            tiltDeg = tiltDeg,
            subject = subject,
        )
    }
}

/**
 * Runs [block] over a CPU-readable, downscaled ARGB copy of this bitmap, then
 * releases the copy. HARDWARE bitmaps have no readable pixels, so they are
 * converted first; a bitmap that is already small and ARGB_8888 is used as-is.
 *
 * The one place in the vertical that calls [Bitmap.getPixels] — keeping it single
 * means the untestable surface stays a few lines wide.
 */
internal inline fun <T> Bitmap.withAnalysisPixels(
    maxSide: Int,
    block: (pixels: IntArray, width: Int, height: Int) -> T,
): T {
    val scaled = scaledToMaxSide(maxSide)
    val readable = if (scaled.config == Bitmap.Config.ARGB_8888) {
        scaled
    } else {
        scaled.copy(Bitmap.Config.ARGB_8888, false) ?: scaled
    }
    return try {
        val width = readable.width
        val height = readable.height
        val pixels = IntArray(width * height)
        readable.getPixels(pixels, 0, width, 0, 0, width, height)
        block(pixels, width, height)
    } finally {
        if (readable !== scaled && !readable.isRecycled) readable.recycle()
        if (scaled !== this && !scaled.isRecycled) scaled.recycle()
    }
}
