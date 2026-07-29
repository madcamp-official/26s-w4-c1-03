package com.gamdo.app.camera

import kotlin.math.roundToInt

/**
 * A rectangle in capture-buffer coordinates — `android.graphics.Rect` without the
 * `android.*` import, so [captureGeometryFor] stays runnable under
 * `testDebugUnitTest` (this module has no `androidTest` source set and no
 * Robolectric).
 *
 * Half-open, like `Rect`: [right] and [bottom] are one past the last pixel.
 */
data class CropRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * One source rectangle plus one transform — everything the shutter needs to turn a
 * freshly decoded capture into the upright, correctly framed photo.
 *
 * [srcX]/[srcY]/[srcWidth]/[srcHeight] are in the decoded buffer's coordinates and
 * are read straight into `Bitmap.createBitmap(src, x, y, w, h, matrix, filter)`;
 * [rotationDegrees] and [mirror] describe the matrix. [outWidth]/[outHeight] are
 * what comes out, and exist so a caller can assert rather than assume.
 */
data class CaptureGeometry(
    val srcX: Int,
    val srcY: Int,
    val srcWidth: Int,
    val srcHeight: Int,
    val rotationDegrees: Int,
    val mirror: Boolean,
    val outWidth: Int,
    val outHeight: Int,
) {
    /**
     * True when the plan asks for nothing — a buffer of exactly [bufferWidth] ×
     * [bufferHeight] that needs no crop, no rotation and no mirror.
     *
     * Worth its own case: `Bitmap.createBitmap` copies unconditionally, so without
     * this the "nothing to do" path would pay a full-resolution allocation to
     * produce a duplicate. The old chain got this for free because each of its
     * steps returned `this` when it had no work.
     */
    fun isNoOp(bufferWidth: Int, bufferHeight: Int): Boolean =
        rotationDegrees == 0 &&
            !mirror &&
            srcX == 0 &&
            srcY == 0 &&
            srcWidth == bufferWidth &&
            srcHeight == bufferHeight
}

/**
 * Collapses the shutter's four framing steps into one.
 *
 * The capture path used to be a chain, each link allocating a fresh
 * full-resolution bitmap:
 *
 * ```
 * decode → cropped(cropRect) → rotated(deg) → [mirroredHorizontally] → centerCropToRatio
 * ```
 *
 * On a 12MP 4:3 capture that is three or four copies of roughly 45MB each, and
 * measurement put the whole block (`CapturePhase.DECODE` + `CapturePhase.CROP`) at
 * **844-953ms** — 59% of a 1528ms shutter on SM-G970N, and the largest app-side
 * stage in every one of the 23 traced captures on 2026-07-29. Every intermediate is
 * discarded; only the last survives. So the steps are composed as *arithmetic* here
 * and applied once.
 *
 * ## Why this is the piece that gets tested
 *
 * Collapsing a pipeline is where framing bugs come from — an off-by-one, a mirror
 * applied on the wrong side of a crop, a rotation inverted. None of that can be
 * caught by looking at a photo, and none of it can be executed on the JVM if it
 * touches `Bitmap`. So the *decision* lives here as integer arithmetic with zero
 * `android.*` imports, and `CaptureGeometryTest` checks it the only way that
 * actually proves anything: by replaying the original four-step chain as a
 * coordinate model and asserting that **every output pixel reads the same source
 * pixel** under both. `Bitmap.transformedBy` is then a thin application of the
 * result.
 *
 * ## The subtlety worth knowing about
 *
 * The centre crop is not exactly centred when the trimmed amount is odd:
 * `centerCropToRatio` used `(width - newWidth) / 2`, which is integer division, so
 * the surviving strip sits half a pixel left of centre. The old chain ran that
 * calculation **after** mirroring, so on the front camera the bias landed on the
 * other side. That is preserved here rather than tidied up — see the `mirror`
 * handling below — because "the front camera's framing moved by one pixel" is not
 * a change this is allowed to smuggle in.
 *
 * @param crop CameraX's viewport rect, or null for none. Clamped to the buffer;
 *   an empty or non-intersecting rect is ignored, matching `Bitmap.cropped`.
 * @param targetRatioWtoH width:height to centre-crop to (0.8 = 4:5, 1.0 = 1:1), or
 *   null to skip the aspect crop.
 */
fun captureGeometryFor(
    bufferWidth: Int,
    bufferHeight: Int,
    crop: CropRect? = null,
    rotationDegrees: Int = 0,
    mirror: Boolean = false,
    targetRatioWtoH: Float? = null,
): CaptureGeometry {
    require(bufferWidth > 0 && bufferHeight > 0) {
        "buffer must be non-empty, was ${bufferWidth}x$bufferHeight"
    }
    val rotation = ((rotationDegrees % 360) + 360) % 360
    require(rotation % 90 == 0) {
        "only quarter turns are exact; $rotationDegrees would need resampling"
    }

    // 1. The viewport crop, reproducing `Bitmap.cropped` exactly: clamp, then
    //    ignore the rect entirely if it is empty or already the whole buffer.
    var cropX = 0
    var cropY = 0
    var cropW = bufferWidth
    var cropH = bufferHeight
    if (crop != null) {
        val left = crop.left.coerceAtLeast(0)
        val top = crop.top.coerceAtLeast(0)
        val right = crop.right.coerceAtMost(bufferWidth)
        val bottom = crop.bottom.coerceAtMost(bufferHeight)
        if (right > left && bottom > top) {
            cropX = left
            cropY = top
            cropW = right - left
            cropH = bottom - top
        }
    }

    // 2. Quarter turns swap the axes; the pixel count is unchanged.
    val quarterTurn = rotation == 90 || rotation == 270
    val uprightW = if (quarterTurn) cropH else cropW
    val uprightH = if (quarterTurn) cropW else cropH

    // 3. The aspect crop, in the coordinates it originally ran in — *after* the
    //    mirror. Same integer rounding as `centerCropToRatio`, deliberately.
    var keepX = 0
    var keepY = 0
    var keepW = uprightW
    var keepH = uprightH
    if (targetRatioWtoH != null && targetRatioWtoH > 0f) {
        if (uprightW.toFloat() / uprightH.toFloat() > targetRatioWtoH) {
            keepW = (uprightH * targetRatioWtoH).roundToInt().coerceIn(1, uprightW)
            keepX = (uprightW - keepW) / 2
        } else {
            keepH = (uprightW / targetRatioWtoH).roundToInt().coerceIn(1, uprightH)
            keepY = (uprightH - keepH) / 2
        }
    }

    // 4. Undo the mirror, because step 3's rect is in mirrored coordinates while
    //    steps 5's rect is in the buffer's. Cropping a mirrored image at `keepX`
    //    reads the un-mirrored columns `[W - keepX - keepW, W - keepX)`. When the
    //    trim is odd these differ by one pixel from `keepX`, which is exactly the
    //    front-camera behaviour the old chain had.
    if (mirror) keepX = uprightW - keepX - keepW

    // 5. Rotate the surviving rect back into buffer coordinates. Clockwise, y-down,
    //    matching `Matrix.postRotate`.
    val (srcX, srcY, srcW, srcH) = when (rotation) {
        90 -> Quad(keepY, cropH - keepX - keepW, keepH, keepW)
        180 -> Quad(cropW - keepX - keepW, cropH - keepY - keepH, keepW, keepH)
        270 -> Quad(cropW - keepY - keepH, keepX, keepH, keepW)
        else -> Quad(keepX, keepY, keepW, keepH)
    }

    return CaptureGeometry(
        srcX = srcX + cropX,
        srcY = srcY + cropY,
        srcWidth = srcW,
        srcHeight = srcH,
        rotationDegrees = rotation,
        mirror = mirror,
        outWidth = keepW,
        outHeight = keepH,
    )
}

/** Four ints with a destructuring declaration, so step 5 reads as one table. */
private data class Quad(val a: Int, val b: Int, val c: Int, val d: Int)
