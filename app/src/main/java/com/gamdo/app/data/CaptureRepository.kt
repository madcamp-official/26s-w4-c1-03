package com.gamdo.app.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.gamdo.app.camera.rotated
import com.gamdo.app.core.Ulid
import com.gamdo.app.data.local.CaptureEditStackDao
import com.gamdo.app.data.local.CapturesDao
import com.gamdo.app.data.local.entity.CaptureEditStack
import com.gamdo.app.data.local.entity.Captures
import com.gamdo.app.edit.EditStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/** `captures.source` vocabulary (DB schema v2.0 §3.8). */
enum class CaptureSource(val value: String) {
    CAMERA_MANUAL("camera_manual"),
    GALLERY_IMPORT("gallery_import"),
}

data class SavedCapture(
    val id: String,
    val filePath: String,
    val source: String,
    val savedToGallery: Boolean,
)

/** Result of writing an edited derivative. [filePath] is never the original. */
data class SavedEdit(
    val captureId: String,
    val filePath: String,
    val stepsRecorded: Int,
    val savedToGallery: Boolean,
)

/**
 * Shutter-moment context stored alongside a capture.
 *
 * Shared contract with guide-capture-agent: they fill this at the shutter (session,
 * overlay/frame analysis, tilt-shake-light conditions) and hand it to
 * [CaptureRepository.saveCameraCapture]. Writes to `sessions` / `session_guides`
 * stay in their `GuideKpiRepository`; this repository owns the `captures` row only.
 *
 * All three fields are JSON documents, defaulted to empty so a caller with nothing
 * to record does not have to invent values.
 */
data class CaptureSnapshot(
    val sessionId: String? = null,
    val analysisJson: String = "{}",
    val conditionsJson: String = "{}",
    val problemsJson: String = "[]",
)

/**
 * Writes the rows the local edit pipeline needs but `CapturesDao` does not cover.
 *
 * Kept as an interface so [CaptureRepository] has no hard dependency on Room, which
 * is what lets the repository be constructed in a test or a preview without a
 * database. [RoomEditStackRecorder] is the production implementation.
 */
interface EditStackRecorder {
    /** Inserts the non-destructive edit steps for one capture (D8-6). */
    suspend fun recordSteps(steps: List<CaptureEditStack>)

    /** Sets `captures.saved_to_gallery` (§4-2 save action). */
    suspend fun markSavedToGallery(captureId: String, saved: Boolean)

    /** Sets `captures.selected_result_id` when the user picks a variant. */
    suspend fun setSelectedResult(captureId: String, resultId: String?)
}

/** [EditStackRecorder] backed by `data/local/EditDaos.kt`. */
class RoomEditStackRecorder(
    private val dao: CaptureEditStackDao,
) : EditStackRecorder {

    override suspend fun recordSteps(steps: List<CaptureEditStack>) {
        if (steps.isEmpty()) return
        dao.insertAll(steps)
    }

    override suspend fun markSavedToGallery(captureId: String, saved: Boolean) {
        dao.setSavedToGallery(captureId, if (saved) 1 else 0)
    }

    override suspend fun setSelectedResult(captureId: String, resultId: String?) {
        dao.setSelectedResult(captureId, resultId)
    }
}

/**
 * Persists captures and their non-destructive edits.
 *
 * Originals are written once to the app's private `captures/` dir and never
 * rewritten — D8-6 is enforced in [saveEditedResult], which refuses to write to a
 * path equal to `captures.file_path`. Edited pixels always land in a new file and
 * the parameters that produced them go to `capture_edit_stack`, so any edit can be
 * replayed or undone without the original having been touched.
 */
class CaptureRepository(
    context: Context,
    private val capturesDao: CapturesDao,
    private val editStackRecorder: EditStackRecorder? = null,
) {
    private val appContext = context.applicationContext

    /**
     * Saves a camera capture: private original, gallery copy, `captures` row.
     * Orientation is already baked into the bitmap pixels.
     */
    suspend fun saveCameraCapture(
        bitmap: Bitmap,
        snapshot: CaptureSnapshot = CaptureSnapshot(),
        exportToGallery: Boolean = true,
    ): SavedCapture = withContext(Dispatchers.IO) {
        val id = newCaptureId()
        val bytes = bitmap.toJpegBytes()
        val file = writeOriginal(id, bytes)

        val exported = exportToGallery && runCatching { exportToGallery(bytes, file.name) }
            .onFailure { Log.w(TAG, "Gallery export failed (kept local copy)", it) }
            .isSuccess

        insertCapture(
            id = id,
            file = file,
            source = CaptureSource.CAMERA_MANUAL,
            snapshot = snapshot,
            savedToGallery = exported,
        )
    }

    /**
     * §4-3 rescue entry point: copies a photo the user picked from the gallery into
     * the app's private storage and registers it as `source='gallery_import'`.
     *
     * The image is decoded and re-encoded rather than byte-copied. That is
     * deliberate: it drops the source EXIF block, including GPS, so an imported
     * photo cannot carry location data into app storage (D8). EXIF orientation is
     * read first and baked into the pixels so the copy is upright, matching how
     * camera captures are stored.
     *
     * `saved_to_gallery` stays 0 — the photo is already in the user's gallery and
     * this import did not put it there.
     */
    suspend fun importFromGallery(
        uri: Uri,
        snapshot: CaptureSnapshot = CaptureSnapshot(),
    ): SavedCapture = withContext(Dispatchers.IO) {
        val id = newCaptureId()
        val orientationDegrees = readExifRotation(uri)

        val decoded = appContext.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "cannot open $uri" }
            BitmapFactory.decodeStream(input)
        } ?: error("cannot decode $uri")

        val upright = decoded.rotated(orientationDegrees)
        val bytes = upright.toJpegBytes()
        if (upright !== decoded && !decoded.isRecycled) decoded.recycle()

        val file = writeOriginal(id, bytes)
        insertCapture(
            id = id,
            file = file,
            source = CaptureSource.GALLERY_IMPORT,
            snapshot = snapshot,
            savedToGallery = false,
        )
    }

    /**
     * Writes an edited derivative of [captureId] and records how it was produced.
     *
     * D8-6 (blocker): the derivative goes to its own file and the original row's
     * `file_path` is never opened for writing. The guard below is not decoration —
     * it is the last line of defence if a future caller passes a bad path.
     *
     * @param steps produced by `EditPlan.toEditSteps()`; inserted into
     *   `capture_edit_stack` so the edit is reproducible from parameters alone.
     */
    suspend fun saveEditedResult(
        captureId: String,
        edited: Bitmap,
        steps: List<EditStep>,
        exportToGallery: Boolean = true,
        variant: String = "edit",
    ): SavedEdit = withContext(Dispatchers.IO) {
        val capture = capturesDao.get(captureId) ?: error("unknown capture $captureId")
        val original = File(capture.filePath)

        val dir = capturesDir()
        val target = File(dir, "${captureId}_${variant}_${System.currentTimeMillis()}.jpg")
        check(target.canonicalPath != original.canonicalPath) {
            "D8-6 violation: refusing to overwrite the original at ${original.path}"
        }

        val bytes = edited.toJpegBytes()
        target.writeBytes(bytes)

        val now = System.currentTimeMillis()
        val rows = steps.map { step ->
            CaptureEditStack(
                id = "stk_" + Ulid.generate(),
                captureId = captureId,
                stepOrder = step.order,
                stepType = step.type.value,
                paramsJson = step.paramsJson,
                active = 1,
                createdAt = now,
            )
        }
        editStackRecorder?.recordSteps(rows)
            ?: Log.w(TAG, "No EditStackRecorder wired; ${rows.size} edit steps not persisted")

        val exported = exportToGallery && runCatching { exportToGallery(bytes, target.name) }
            .onFailure { Log.w(TAG, "Gallery export failed (kept local copy)", it) }
            .isSuccess
        if (exported) {
            editStackRecorder?.markSavedToGallery(captureId, true)
        }

        SavedEdit(
            captureId = captureId,
            filePath = target.absolutePath,
            stepsRecorded = if (editStackRecorder == null) 0 else rows.size,
            savedToGallery = exported,
        )
    }

    /** §4-2 [저장]: records that the user pushed this capture to their gallery. */
    suspend fun markSavedToGallery(captureId: String, saved: Boolean = true) {
        editStackRecorder?.markSavedToGallery(captureId, saved)
            ?: Log.w(TAG, "No EditStackRecorder wired; saved_to_gallery not updated")
    }

    private suspend fun insertCapture(
        id: String,
        file: File,
        source: CaptureSource,
        snapshot: CaptureSnapshot,
        savedToGallery: Boolean,
    ): SavedCapture {
        capturesDao.insert(
            Captures(
                id = id,
                sessionId = snapshot.sessionId,
                source = source.value,
                filePath = file.absolutePath,
                analysisJson = snapshot.analysisJson,
                conditionsJson = snapshot.conditionsJson,
                problemsJson = snapshot.problemsJson,
                savedToGallery = if (savedToGallery) 1 else 0,
                createdAt = System.currentTimeMillis(),
            ),
        )
        return SavedCapture(
            id = id,
            filePath = file.absolutePath,
            source = source.value,
            savedToGallery = savedToGallery,
        )
    }

    private fun newCaptureId(): String = "cap_" + Ulid.generate()

    private fun capturesDir(): File = File(appContext.filesDir, "captures").apply { mkdirs() }

    private fun writeOriginal(id: String, bytes: ByteArray): File =
        File(capturesDir(), "$id.jpg").apply { writeBytes(bytes) }

    private fun Bitmap.toJpegBytes(quality: Int = JPEG_QUALITY): ByteArray =
        ByteArrayOutputStream().use { out ->
            compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        }

    /** Degrees of clockwise rotation needed to display the image upright. */
    private fun readExifRotation(uri: Uri): Int = runCatching {
        appContext.contentResolver.openInputStream(uri).use { input ->
            if (input == null) return@runCatching 0
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
        }
    }.getOrDefault(0)

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
        const val JPEG_QUALITY = 95
    }
}
