package com.gamdo.app.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.gamdo.app.core.Ulid
import com.gamdo.app.data.local.CapturesDao
import com.gamdo.app.data.local.entity.Captures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

data class SavedCapture(val id: String, val filePath: String)

/**
 * Persists camera captures (§1-5): writes the JPEG to the app's private
 * `captures/` dir (non-destructive original), exports a copy to the gallery via
 * MediaStore, and records a row in the `captures` table. Orientation is already
 * baked into the bitmap pixels, so the gallery shows it upright.
 */
class CaptureRepository(
    context: Context,
    private val capturesDao: CapturesDao,
) {
    private val appContext = context.applicationContext

    suspend fun saveCameraCapture(bitmap: Bitmap): SavedCapture = withContext(Dispatchers.IO) {
        val id = "cap_" + Ulid.generate()
        val fileName = "$id.jpg"

        val bytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            out.toByteArray()
        }

        val dir = File(appContext.filesDir, "captures").apply { mkdirs() }
        val file = File(dir, fileName)
        file.writeBytes(bytes)

        runCatching { exportToGallery(bytes, fileName) }
            .onFailure { Log.w(TAG, "Gallery export failed (kept local copy)", it) }

        capturesDao.insert(
            Captures(
                id = id,
                sessionId = null,
                source = "camera_manual",
                filePath = file.absolutePath,
                createdAt = System.currentTimeMillis(),
            ),
        )

        SavedCapture(id = id, filePath = file.absolutePath)
    }

    /** Inserts a JPEG copy into the gallery under Pictures/감도. */
    private fun exportToGallery(bytes: ByteArray, displayName: String) {
        val resolver = appContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/감도")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore insert returned null")
        resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("openOutputStream null")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
    }

    private companion object {
        const val TAG = "CaptureRepository"
    }
}
