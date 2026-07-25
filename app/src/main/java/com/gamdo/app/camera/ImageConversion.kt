package com.gamdo.app.camera

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy

/**
 * Converts an analysis [ImageProxy] (YUV_420_888) to an upright RGB [Bitmap],
 * applying the sensor rotation. Downscaling is handled by the ImageAnalysis
 * resolution selector (≈640px long side), so this stays cheap. (§2-1)
 *
 * CameraX's [ImageProxy.toBitmap] does the YUV→RGB conversion; [rotated] bakes in
 * the orientation. ML Kit's InputImage path (which can skip the RGB copy) is
 * added alongside this in §2-2.
 */
fun ImageProxy.toAnalysisBitmap(): Bitmap =
    toBitmap().rotated(imageInfo.rotationDegrees)

/**
 * Mean luma of the frame in 0..1, sampled every [stride] pixels from the Y plane
 * (YUV_420_888). Rotation-invariant, ~5k samples at 640×480 — cheap enough to run
 * per analyzed frame. Feeds FrameFeatures.brightnessMean (§2-1).
 */
fun ImageProxy.lumaMean(stride: Int = 8): Float {
    val plane = planes.firstOrNull() ?: return 0f
    val buffer = plane.buffer.duplicate().apply { rewind() }
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val limit = buffer.limit()
    var sum = 0L
    var count = 0
    var y = 0
    while (y < height) {
        val rowBase = y * rowStride
        var x = 0
        while (x < width) {
            val index = rowBase + x * pixelStride
            if (index >= limit) break
            sum += buffer.get(index).toInt() and 0xFF
            count++
            x += stride
        }
        y += stride
    }
    return if (count == 0) 0f else (sum.toFloat() / count) / 255f
}
