package com.gamdo.app.edit

import kotlin.math.abs
import kotlin.math.cos
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

    val cropWi = cropW.roundToInt().coerceIn(1, rotW)
    val cropHi = cropH.roundToInt().coerceIn(1, rotH)

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
