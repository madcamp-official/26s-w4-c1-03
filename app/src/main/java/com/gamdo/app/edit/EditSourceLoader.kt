package com.gamdo.app.edit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.gamdo.app.camera.scaledToMaxSide
import java.io.File

/**
 * Decodes a stored capture at a bounded resolution — the Android half of the §4-1
 * memory budget.
 *
 * Kept to "decode and delegate", like `ImageMetricsExtractor`: the size arithmetic is
 * [inSampleSizeFor] in `RenderBudget.kt`, which is JVM-tested, and this file only
 * hands the number to `BitmapFactory`.
 *
 * Subsampling matters more than it looks. `BitmapFactory.decodeFile` on a 4000x3000
 * JPEG allocates 48 MB before anything can be scaled down; `inSampleSize` makes the
 * decoder emit the smaller bitmap directly, so the peak never happens.
 */
object EditSourceLoader {

    /**
     * Decodes [file] with its longer edge at or below [maxSide], or null if the file
     * cannot be read as an image.
     *
     * Two stages: a power-of-two `inSampleSize` during decode (cheap, but only hits
     * powers of two), then an exact scale if that left the image larger than
     * [maxSide]. The intermediate is recycled.
     */
    fun decode(file: File, maxSide: Int): Bitmap? {
        if (!file.isFile) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = inSampleSizeFor(bounds.outWidth, bounds.outHeight, maxSide)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(file.path, options) ?: return null

        val fitted = decoded.scaledToMaxSide(maxSide)
        if (fitted !== decoded && !decoded.isRecycled) decoded.recycle()
        return fitted
    }

    /** Pixel size of [file] without decoding it, or null when it is not an image. */
    fun readSize(file: File): Pair<Int, Int>? {
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return bounds.outWidth to bounds.outHeight
    }
}
