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

/**
 * Crops to [rect] (buffer coordinates), clamped to the bitmap bounds. Returns the
 * original when the rect is degenerate or already covers the full bitmap — used to
 * apply CameraX's viewport cropRect so captures match what the preview showed.
 */
fun Bitmap.cropped(rect: Rect): Bitmap {
    val r = Rect(rect)
    if (!r.intersect(0, 0, width, height)) return this
    if (r.width() <= 0 || r.height() <= 0) return this
    if (r.width() == width && r.height() == height) return this
    return Bitmap.createBitmap(this, r.left, r.top, r.width(), r.height())
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

/** Mirrors horizontally — used so front-camera captures match the preview. */
fun Bitmap.mirroredHorizontally(): Bitmap {
    val matrix = Matrix().apply { preScale(-1f, 1f) }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}

/**
 * Center-crops to the target width:height ratio (e.g. 0.8 for 4:5, 1.0 for 1:1),
 * keeping the subject centered.
 */
fun Bitmap.centerCropToRatio(ratioWtoH: Float): Bitmap {
    val currentRatio = width.toFloat() / height.toFloat()
    return if (currentRatio > ratioWtoH) {
        // too wide → trim width
        val newWidth = (height * ratioWtoH).roundToInt().coerceAtMost(width)
        val x = (width - newWidth) / 2
        Bitmap.createBitmap(this, x, 0, newWidth, height)
    } else {
        // too tall → trim height
        val newHeight = (width / ratioWtoH).roundToInt().coerceAtMost(height)
        val y = (height - newHeight) / 2
        Bitmap.createBitmap(this, 0, y, width, newHeight)
    }
}
