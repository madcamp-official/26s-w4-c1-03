package com.gamdo.app.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import com.gamdo.app.data.ReferenceImagePreprocessor
import java.io.File
import java.io.FileOutputStream

/**
 * Normalizes a Photo Picker image before a reference-analysis upload. Re-encoding
 * is intentional: it removes the complete metadata block, not only GPS tags,
 * and makes EXIF orientation part of the pixels sent to the server.
 */
class AndroidReferenceImagePreprocessor(
    private val maxSide: Int = 4096,
) : ReferenceImagePreprocessor {
    override fun normalize(file: File) {
        val source = BitmapFactory.decodeFile(file.absolutePath)
            ?: error("reference image could not be decoded")
        val oriented = source.applyExifOrientation(file)
        val scaled = oriented.scaleDown(maxSide)
        val temporary = File(file.parentFile, "${file.name}.normalized")
        try {
            FileOutputStream(temporary).use { output ->
                check(scaled.compress(Bitmap.CompressFormat.JPEG, 94, output)) {
                    "reference image could not be encoded"
                }
            }
            check(file.delete() && temporary.renameTo(file)) { "reference image could not be replaced" }
        } finally {
            source.recycleIfDifferent(oriented, scaled)
            temporary.delete()
        }
    }

    private fun Bitmap.applyExifOrientation(file: File): Bitmap {
        val orientation = runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return this
        }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }

    private fun Bitmap.scaleDown(maxSide: Int): Bitmap {
        val longest = maxOf(width, height)
        if (longest <= maxSide) return this
        val ratio = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(this, (width * ratio).toInt(), (height * ratio).toInt(), true)
    }

    private fun Bitmap.recycleIfDifferent(vararg candidates: Bitmap) {
        candidates.distinct().filter { it !== this }.forEach { it.recycle() }
        recycle()
    }
}
