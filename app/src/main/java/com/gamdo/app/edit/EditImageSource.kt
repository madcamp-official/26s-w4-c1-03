package com.gamdo.app.edit

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import com.gamdo.app.camera.rotated
import com.gamdo.app.camera.scaledToMaxSide
import java.io.File

/**
 * The one place the editor reads its source pixels from.
 *
 * Before W3.5-6 the result screen read `File(capture.filePath)` in three places —
 * the preview decode, the size probe that scales the geometry plan, and the
 * full-resolution re-decode on save. A `MediaStore` photo has no usable file path
 * (scoped storage; `_data` is unreadable and deprecated), so opening one meant
 * either a `Uri` branch at each of those three sites or a single seam. This is the
 * seam: the screen holds one [EditImageSource] and never asks what is behind it.
 *
 * Implementations are `remember`ed by the caller and compared by identity — they are
 * `produceState` keys, and a fresh instance per recomposition would re-decode the
 * photo on every frame.
 */
interface EditImageSource {

    /** Decodes with the longer edge at or below [maxSide], or null if unreadable. */
    fun decode(maxSide: Int): Bitmap?

    /**
     * Pixel size as it will be *displayed* (EXIF rotation already accounted for),
     * without decoding, or null when it is not an image.
     */
    fun readSize(): Pair<Int, Int>?
}

/** An app capture, or an AI 3 candidate — a file this app wrote and owns. */
class FileEditImageSource(private val file: File) : EditImageSource {

    // No EXIF handling on purpose. Everything this app writes has orientation baked
    // into the pixels (`CaptureRepository.saveCameraCapture` / `importFromGallery`
    // both rotate before encoding), so reading a tag here could only ever
    // double-rotate a photo that is already upright.
    override fun decode(maxSide: Int): Bitmap? = EditSourceLoader.decode(file, maxSide)

    override fun readSize(): Pair<Int, Int>? = EditSourceLoader.readSize(file)

    override fun toString(): String = "FileEditImageSource(${file.path})"
}

/**
 * A photo in the user's library, reached through `MediaStore` (W3.5-6).
 *
 * **Read-only, structurally.** The only resolver call in here is
 * `openInputStream`; there is no path by which this class can write to the user's
 * photo. That is D8-6 (비파괴 보존) for a file the app does not own — see
 * [com.gamdo.app.ui.result.saveTargetFor] for where the edited pixels go instead.
 *
 * Unlike [FileEditImageSource] this one **does** apply EXIF orientation. A phone
 * camera stores its frames in sensor orientation with a tag saying how to turn
 * them, and `BitmapFactory` ignores that tag. Coil applies it, so the album
 * thumbnail is already upright — without this the editor would be the one screen
 * that shows the photo on its side.
 */
class UriEditImageSource(
    private val resolver: ContentResolver,
    private val uri: Uri,
) : EditImageSource {

    override fun decode(maxSide: Int): Bitmap? {
        val bounds = readBounds() ?: return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = inSampleSizeFor(bounds.width, bounds.height, maxSide)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        // A second stream, not a reset: `openInputStream` may hand back a
        // non-markable stream, and the bounds pass has already consumed it.
        val decoded = open { input -> BitmapFactory.decodeStream(input, null, options) } ?: return null

        val upright = decoded.rotated(bounds.rotationDegrees)
        if (upright !== decoded && !decoded.isRecycled) decoded.recycle()

        val fitted = upright.scaledToMaxSide(maxSide)
        if (fitted !== upright && !upright.isRecycled) upright.recycle()
        return fitted
    }

    override fun readSize(): Pair<Int, Int>? {
        val bounds = readBounds() ?: return null
        // Displayed size, so a portrait photo stored as landscape-plus-a-tag does not
        // hand the geometry planner a landscape frame to crop.
        return if (bounds.rotationDegrees % 180 == 0) {
            bounds.width to bounds.height
        } else {
            bounds.height to bounds.width
        }
    }

    private class Bounds(val width: Int, val height: Int, val rotationDegrees: Int)

    private fun readBounds(): Bounds? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open { input -> BitmapFactory.decodeStream(input, null, options) }
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        return Bounds(options.outWidth, options.outHeight, readExifRotation())
    }

    /** Clockwise degrees needed to display the image upright; 0 on any failure. */
    private fun readExifRotation(): Int = open { input ->
        when (
            ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        ) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } ?: 0

    /**
     * One `openInputStream` + `use`, with every documented failure treated the same.
     *
     * A photo can vanish between the album listing it and the user tapping it
     * (deleted from another app), the provider can refuse (`SecurityException` after
     * a partial-access grant changed), and a corrupt file simply decodes to null.
     * All three mean "this cannot be opened", which the screen already has a state
     * for; none of them may take the process down.
     */
    private fun <T> open(block: (java.io.InputStream) -> T?): T? = runCatching {
        resolver.openInputStream(uri).use { input ->
            if (input == null) null else block(input)
        }
    }.getOrElse {
        Log.w(TAG, "could not read $uri", it)
        null
    }

    override fun toString(): String = "UriEditImageSource($uri)"

    private companion object {
        const val TAG = "UriEditImageSource"
    }
}
