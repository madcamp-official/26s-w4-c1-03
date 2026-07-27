package com.gamdo.app.camera

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import com.gamdo.app.detect.BrightnessSample
import com.gamdo.app.detect.NormalizedBox
import com.gamdo.app.guide.SceneFrameSignals

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

/**
 * Samples frame, face and non-face background luma in one Y-plane pass.
 * [faceBox] is expressed in the upright ML Kit coordinate space; raw Y-plane
 * samples are rotated into that space before region classification.
 */
fun ImageProxy.brightnessSample(faceBox: NormalizedBox?, stride: Int = 8): BrightnessSample {
    val plane = planes.firstOrNull() ?: return BrightnessSample(frameMean = 0f)
    val buffer = plane.buffer.duplicate().apply { rewind() }
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val limit = buffer.limit()
    var frameSum = 0L
    var faceSum = 0L
    var backgroundSum = 0L
    var frameCount = 0
    var faceCount = 0
    var backgroundCount = 0
    var y = 0
    while (y < height) {
        val rowBase = y * rowStride
        var x = 0
        while (x < width) {
            val index = rowBase + x * pixelStride
            if (index >= limit) break
            val value = buffer.get(index).toInt() and 0xFF
            frameSum += value
            frameCount++
            if (faceBox != null) {
                val u = uprightX(x, y)
                val v = uprightY(x, y)
                if (u >= faceBox.left && u <= faceBox.right &&
                    v >= faceBox.top && v <= faceBox.bottom
                ) {
                    faceSum += value
                    faceCount++
                } else {
                    backgroundSum += value
                    backgroundCount++
                }
            }
            x += stride
        }
        y += stride
    }
    val frameMean = mean(frameSum, frameCount)
    return BrightnessSample(
        frameMean = frameMean,
        faceMean = meanOrNull(faceSum, faceCount),
        backgroundMean = meanOrNull(backgroundSum, backgroundCount),
    )
}

/**
 * Extracts the small structural signal consumed by [SceneStructureAnalyzer].
 * This stays on the Y plane: no RGB bitmap allocation and no server upload.
 * [subjectBox] uses the same upright normalized coordinates as ML Kit output.
 */
fun ImageProxy.sceneFrameSignals(
    subjectBox: NormalizedBox?,
    stride: Int = 16,
    rowBins: Int = 12,
): SceneFrameSignals {
    val plane = planes.firstOrNull() ?: return SceneFrameSignals()
    val buffer = plane.buffer.duplicate().apply { rewind() }
    val rowSums = FloatArray(rowBins)
    val rowCounts = IntArray(rowBins)
    val sideSums = FloatArray(2)
    val sideCounts = IntArray(2)
    val limit = buffer.limit()
    val safeStride = stride.coerceAtLeast(2)
    var y = 0
    while (y < height) {
        val rowBase = y * plane.rowStride
        var x = 0
        while (x + safeStride < width) {
            val index = rowBase + x * plane.pixelStride
            val nextIndex = rowBase + (x + safeStride) * plane.pixelStride
            if (index >= limit || nextIndex >= limit) break
            val value = (buffer.get(index).toInt() and 0xFF) / 255f
            val next = (buffer.get(nextIndex).toInt() and 0xFF) / 255f
            val u = uprightX(x, y)
            val v = uprightY(x, y)
            val row = (v * rowBins).toInt().coerceIn(0, rowBins - 1)
            rowSums[row] += value
            rowCounts[row]++
            val side = when {
                subjectBox != null && u < subjectBox.left -> 0
                subjectBox != null && u > subjectBox.right -> 1
                else -> -1
            }
            if (side >= 0) {
                sideSums[side] += kotlin.math.abs(value - next)
                sideCounts[side]++
            }
            x += safeStride
        }
        y += safeStride
    }
    return SceneFrameSignals(
        rowLuminance = rowSums.indices.map { index ->
            if (rowCounts[index] == 0) 0f else rowSums[index] / rowCounts[index]
        },
        sideEdgeDensity = sideSums.indices.map { index ->
            if (sideCounts[index] == 0) 1f else (sideSums[index] / sideCounts[index]).coerceIn(0f, 1f)
        },
    )
}

private fun ImageProxy.uprightX(rawX: Int, rawY: Int): Float {
    val x = rawX.toFloat() / width.coerceAtLeast(1)
    val y = rawY.toFloat() / height.coerceAtLeast(1)
    return when (imageInfo.rotationDegrees) {
        90 -> 1f - y
        180 -> 1f - x
        270 -> y
        else -> x
    }
}

private fun ImageProxy.uprightY(rawX: Int, rawY: Int): Float {
    val x = rawX.toFloat() / width.coerceAtLeast(1)
    val y = rawY.toFloat() / height.coerceAtLeast(1)
    return when (imageInfo.rotationDegrees) {
        90 -> x
        180 -> 1f - y
        270 -> 1f - x
        else -> y
    }
}

private fun mean(sum: Long, count: Int): Float =
    if (count == 0) 0f else (sum.toFloat() / count) / 255f

private fun meanOrNull(sum: Long, count: Int): Float? =
    if (count == 0) null else (sum.toFloat() / count) / 255f
