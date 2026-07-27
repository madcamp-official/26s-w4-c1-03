package com.gamdo.app.edit

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * §4-1 geometry stage — **platform-free**.
 *
 * Produces the numbers a renderer needs (a rotation angle and a crop rectangle in
 * source pixel coordinates) without ever touching `android.graphics.Matrix` or a
 * Bitmap. The renderer just feeds these into `Matrix.postRotate` /
 * `Bitmap.createBitmap`, so the whole stage is verifiable on the JVM.
 */

/**
 * D9-1: the product ships exactly two aspect ratios. Do not add 16:9, 3:4 or full.
 */
enum class EditAspect(val presetKey: String, val ratioWtoH: Float) {
    RATIO_4_5("4:5", 0.8f),
    RATIO_1_1("1:1", 1f),
    ;

    companion object {
        /** Parses a preset's `composition.targetAspectRatio`; unknown keys fall back to 4:5. */
        fun fromPresetKey(key: String?): EditAspect =
            entries.firstOrNull { it.presetKey == key } ?: RATIO_4_5

        /** Nearest supported ratio to [ratioWtoH] — used to mirror the camera's choice. */
        fun nearest(ratioWtoH: Float): EditAspect =
            entries.minByOrNull { abs(it.ratioWtoH - ratioWtoH) } ?: RATIO_4_5
    }
}

/** Crop rectangle in source pixels, origin top-left. */
data class CropRect(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height
}

/**
 * Output of the geometry stage.
 *
 * [rotationDeg] is applied first (positive = clockwise), then [crop] is taken from
 * the *rotated* image. [marginExpansionCandidate] is set when levelling would leave
 * empty corners that the aspect crop cannot absorb — §4-1 says to mark the frame as
 * a generative margin-expansion candidate rather than shrink it further.
 */
data class GeometryPlan(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val rotationDeg: Float,
    val rotatedWidth: Int,
    val rotatedHeight: Int,
    val crop: CropRect,
    val aspect: EditAspect,
    val marginExpansionCandidate: Boolean,
)

/** Levelling is capped: past this the frame is a deliberate dutch angle, not a mistake. */
const val MAX_LEVELING_DEG = 12f

/** Below this the rotation costs more resolution than it buys. */
const val MIN_LEVELING_DEG = 0.35f

/**
 * Rotation that cancels a measured [tiltDeg]. Returns 0 inside the dead band and
 * clamps at ±[maxDeg].
 *
 * ## Sign
 *
 * `camera/TiltSensor.kt`'s `TiltReading` KDoc is the single source of truth and
 * derives `rollDeg < 0` = clockwise device tilt, so cancelling it is `-tiltDeg` =
 * clockwise correction. That matches this module: [toAffineMatrixValues] lays out
 * `[cos, -sin; sin, cos]`, byte-identical to what `android.graphics.Matrix.setRotate`
 * builds, so positive degrees rotate clockwise here exactly as `postRotate` does.
 * `RenderMatrixTest` pins it — a point right of centre must rise under a negative
 * rotation.
 *
 * **Derived, not observed.** The convention was reasoned out from the gravity-vector
 * contract with no device attached, and guide-capture-agent has flagged that
 * `CameraOverlay`'s horizon line may carry the opposite sign. Both signs come from
 * this one derivation, so they stand or fall together: if the device check shows the
 * overlay is right, this `-tiltDeg` inverts with it. DONE-DEVICE, and harmless until
 * §3-3 starts populating `conditions_json` in wave 3.
 */
fun levelingRotationDeg(tiltDeg: Float, maxDeg: Float = MAX_LEVELING_DEG): Float {
    if (!tiltDeg.isFinite()) return 0f
    if (abs(tiltDeg) < MIN_LEVELING_DEG) return 0f
    return (-tiltDeg).coerceIn(-maxDeg, maxDeg)
}

/** Bounding box of a [width]x[height] rect rotated by [angleDeg]. */
fun rotatedBounds(width: Int, height: Int, angleDeg: Float): Pair<Int, Int> {
    val rad = Math.toRadians(angleDeg.toDouble())
    val c = abs(cos(rad))
    val s = abs(sin(rad))
    val w = (width * c + height * s).roundToInt().coerceAtLeast(1)
    val h = (width * s + height * c).roundToInt().coerceAtLeast(1)
    return w to h
}

/**
 * Largest axis-aligned rectangle **of the same proportions** that fits entirely
 * inside a [width]x[height] rect rotated by [angleDeg] — i.e. the region with no
 * empty corners. Returns width/height in source-pixel units.
 */
fun largestInnerRect(width: Int, height: Int, angleDeg: Float): Pair<Float, Float> {
    if (width <= 0 || height <= 0) return 0f to 0f
    val rad = Math.toRadians(abs(angleDeg.toDouble()) % 180.0)
    val sinA = abs(sin(rad))
    val cosA = abs(cos(rad))
    if (sinA < 1e-9) return width.toFloat() to height.toFloat()

    val w = width.toDouble()
    val h = height.toDouble()
    val widthIsLonger = w >= h
    val longSide = if (widthIsLonger) w else h
    val shortSide = if (widthIsLonger) h else w

    return if (shortSide <= 2.0 * sinA * cosA * longSide || abs(sinA - cosA) < 1e-10) {
        val half = 0.5 * shortSide
        val (rw, rh) = if (widthIsLonger) (half / sinA) to (half / cosA) else (half / cosA) to (half / sinA)
        rw.toFloat() to rh.toFloat()
    } else {
        val cos2a = cosA * cosA - sinA * sinA
        val rw = (w * cosA - h * sinA) / cos2a
        val rh = (h * cosA - w * sinA) / cos2a
        rw.toFloat() to rh.toFloat()
    }
}

/**
 * Full geometry stage: level by [tiltDeg], then take the largest [aspect] crop that
 * stays inside the rotation-safe area, biased toward the subject.
 *
 * [subjectCenterX]/[subjectCenterY] are normalized 0..1 in the *source* frame; with
 * no subject detection they default to the centre, which reproduces the current
 * `centerCropToRatio` behaviour.
 */
fun planGeometry(
    sourceWidth: Int,
    sourceHeight: Int,
    tiltDeg: Float,
    aspect: EditAspect,
    subjectCenterX: Float = 0.5f,
    subjectCenterY: Float = 0.5f,
): GeometryPlan {
    require(sourceWidth > 0 && sourceHeight > 0) { "source must be non-empty" }

    val rotation = levelingRotationDeg(tiltDeg)
    val (rotW, rotH) = if (rotation == 0f) {
        sourceWidth to sourceHeight
    } else {
        rotatedBounds(sourceWidth, sourceHeight, rotation)
    }

    // Region of the rotated canvas that contains no empty corners.
    val (safeW, safeH) = if (rotation == 0f) {
        sourceWidth.toFloat() to sourceHeight.toFloat()
    } else {
        largestInnerRect(sourceWidth, sourceHeight, rotation)
    }

    // Largest rect of the target ratio inside that safe area.
    var cropW = safeW
    var cropH = cropW / aspect.ratioWtoH
    if (cropH > safeH) {
        cropH = safeH
        cropW = cropH * aspect.ratioWtoH
    }

    // floor, not round: rounding up by a sub-pixel would push the crop one column
    // past the rotation-safe area and leave a 1px transparent sliver on one edge.
    val cropWi = floor(cropW).toInt().coerceIn(1, rotW)
    val cropHi = floor(cropH).toInt().coerceIn(1, rotH)

    // Centre on the subject, then clamp so the window stays on the safe area.
    val safeLeft = ((rotW - safeW) / 2f).coerceAtLeast(0f)
    val safeTop = ((rotH - safeH) / 2f).coerceAtLeast(0f)
    val desiredX = subjectCenterX.coerceIn(0f, 1f) * rotW - cropWi / 2f
    val desiredY = subjectCenterY.coerceIn(0f, 1f) * rotH - cropHi / 2f
    val maxX = (safeLeft + safeW - cropWi).coerceAtLeast(safeLeft)
    val maxY = (safeTop + safeH - cropHi).coerceAtLeast(safeTop)
    val x = desiredX.coerceIn(safeLeft, maxX).roundToInt().coerceIn(0, (rotW - cropWi).coerceAtLeast(0))
    val y = desiredY.coerceIn(safeTop, maxY).roundToInt().coerceIn(0, (rotH - cropHi).coerceAtLeast(0))

    // §4-1: if levelling ate more than this share of the frame, the honest answer is
    // to mark the shot for margin expansion instead of cropping in further.
    val areaKept = (cropWi.toFloat() * cropHi) / (sourceWidth.toFloat() * sourceHeight)
    val marginExpansion = rotation != 0f && areaKept < MIN_AREA_KEPT

    return GeometryPlan(
        sourceWidth = sourceWidth,
        sourceHeight = sourceHeight,
        rotationDeg = rotation,
        rotatedWidth = rotW,
        rotatedHeight = rotH,
        crop = CropRect(x, y, cropWi, cropHi),
        aspect = aspect,
        marginExpansionCandidate = marginExpansion,
    )
}

/** Below this fraction of the original area, levelling has cost too much frame. */
const val MIN_AREA_KEPT = 0.45f

/**
 * Longest side the pipeline processes at. §4-1 allows dropping to
 * [PREVIEW_MAX_SIDE] when the full-resolution pass misses the 2s budget, then
 * re-applying the same plan at [FULL_MAX_SIDE] on save.
 */
const val FULL_MAX_SIDE = 4000
const val PREVIEW_MAX_SIDE = 2000

/**
 * The whole geometry stage as one affine transform, source pixels → output pixels.
 *
 * Nine floats in `android.graphics.Matrix.setValues` order
 * (`[a b tx  c d ty  0 0 1]`, so `x' = a*x + b*y + tx`). The renderer feeds this
 * straight into a single `Canvas.drawBitmap(bitmap, matrix, paint)`, which is why
 * levelling and cropping cost one resample instead of two: a `createBitmap`
 * rotation followed by a `createBitmap` crop would allocate an intermediate the size
 * of the rotated bounding box and interpolate the pixels twice.
 *
 * The same nine numbers are a `cv::warpAffine` matrix and an AGSL transform uniform,
 * so this does not commit the pipeline to Canvas.
 *
 * Composition: centre the source, rotate by [rotationDeg], re-centre on the rotated
 * canvas, then shift the crop origin to (0, 0).
 */
fun GeometryPlan.toAffineMatrixValues(): FloatArray {
    val rad = Math.toRadians(rotationDeg.toDouble())
    val cosT = cos(rad).toFloat()
    val sinT = sin(rad).toFloat()
    val halfSrcW = sourceWidth / 2f
    val halfSrcH = sourceHeight / 2f
    val tx = rotatedWidth / 2f - crop.x - (cosT * halfSrcW - sinT * halfSrcH)
    val ty = rotatedHeight / 2f - crop.y - (sinT * halfSrcW + cosT * halfSrcH)
    return floatArrayOf(
        cosT, -sinT, tx,
        sinT, cosT, ty,
        0f, 0f, 1f,
    )
}

/** Maps a source point through [values] (the array from [toAffineMatrixValues]). */
fun mapAffinePoint(values: FloatArray, x: Float, y: Float): Pair<Float, Float> {
    require(values.size == 9) { "affine matrix must have 9 entries" }
    return (values[0] * x + values[1] * y + values[2]) to
        (values[3] * x + values[4] * y + values[5])
}

/** Scales a plan produced at [fromWidth] onto a different working resolution. */
fun GeometryPlan.scaledTo(fromWidth: Int, toWidth: Int): GeometryPlan {
    if (fromWidth <= 0 || toWidth <= 0 || fromWidth == toWidth) return this
    val k = toWidth.toFloat() / fromWidth
    fun s(v: Int): Int = (v * k).roundToInt().coerceAtLeast(1)
    return copy(
        sourceWidth = s(sourceWidth),
        sourceHeight = s(sourceHeight),
        rotatedWidth = s(rotatedWidth),
        rotatedHeight = s(rotatedHeight),
        crop = CropRect(
            x = min(s(crop.x), s(rotatedWidth) - 1),
            y = min(s(crop.y), s(rotatedHeight) - 1),
            width = s(crop.width),
            height = s(crop.height),
        ),
    )
}
