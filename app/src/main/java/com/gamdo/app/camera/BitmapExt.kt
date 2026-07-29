package com.gamdo.app.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import kotlin.math.roundToInt

/** Returns a copy rotated clockwise by [degrees] (0 returns the original). */
fun Bitmap.rotated(degrees: Int): Bitmap {
    if (degrees % 360 == 0) return this
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}

/** Downscales so the longer side is at most [maxSide] px (no-op if already smaller). */
fun Bitmap.scaledToMaxSide(maxSide: Int): Bitmap {
    val longSide = maxOf(width, height)
    if (longSide <= maxSide) return this
    val scale = maxSide.toFloat() / longSide
    return Bitmap.createScaledBitmap(
        this,
        (width * scale).roundToInt().coerceAtLeast(1),
        (height * scale).roundToInt().coerceAtLeast(1),
        true,
    )
}

/**
 * Applies a whole [CaptureGeometry] in a single allocation.
 *
 * This is the shutter path's replacement for
 * `cropped(…).rotated(…).mirroredHorizontally().centerCropToRatio(…)`, which built
 * three or four full-resolution intermediates — ~45MB each on a 12MP capture — and
 * threw all but the last away. The arithmetic that made them collapsible lives in
 * [captureGeometryFor] and is pinned by `CaptureGeometryTest`; this function is
 * only the application of it, which is why it has no logic of its own to get wrong.
 *
 * Returns `this` when there is genuinely nothing to do, because `createBitmap`
 * would otherwise spend a full-resolution copy producing an identical bitmap.
 */
fun Bitmap.transformedBy(plan: CaptureGeometry): Bitmap {
    if (plan.isNoOp(width, height)) return this
    if (plan.rotationDegrees == 0 && !plan.mirror) {
        return Bitmap.createBitmap(this, plan.srcX, plan.srcY, plan.srcWidth, plan.srcHeight)
    }
    val matrix = Matrix().apply {
        postRotate(plan.rotationDegrees.toFloat())
        // After the rotation, matching the old chain's order: it mirrored the
        // already-upright bitmap. Composing them the other way round flips the
        // photo about the wrong axis on 90° and 270°.
        if (plan.mirror) postScale(-1f, 1f)
    }
    return Bitmap.createBitmap(
        this, plan.srcX, plan.srcY, plan.srcWidth, plan.srcHeight, matrix, true,
    )
}

// `cropped`, `mirroredHorizontally` and `centerCropToRatio` used to live here. They
// are gone rather than merely unused: each allocated a full-resolution bitmap, and
// keeping four one-step helpers next to [transformedBy] is an invitation to chain
// them back together — which is the ~500ms this file just removed. The viewport
// crop, the mirror and the aspect crop are all expressed as one [CaptureGeometry].
