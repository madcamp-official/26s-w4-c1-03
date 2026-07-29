package com.gamdo.app.data.media

import com.gamdo.app.data.CaptureRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W3.5-2 — pure shaping of a raw MediaStore row: timestamp resolution and the
 * app's-own-export dedup filter. Zero `android.*` imports; the cursor read that
 * produces a [DevicePhotoRow] lives in [DevicePhotoRepository], which cannot run
 * under a JVM test.
 */
class AlbumEntryTest {

    @Test
    fun `date taken millis wins when present and positive`() {
        val row = DevicePhotoRow(
            mediaStoreId = 1,
            dateTakenMillis = 1_700_000_000_000L,
            dateAddedSeconds = 1_600_000_000L,
            bucketDisplayName = "Camera",
        )
        assertEquals(1_700_000_000_000L, row.resolvedTakenAtMillis())
    }

    @Test
    fun `falls back to date added seconds when date taken is null`() {
        val row = DevicePhotoRow(
            mediaStoreId = 1,
            dateTakenMillis = null,
            dateAddedSeconds = 1_600_000_000L,
            bucketDisplayName = "Camera",
        )
        assertEquals(1_600_000_000_000L, row.resolvedTakenAtMillis())
    }

    @Test
    fun `falls back to date added seconds when date taken is zero`() {
        // Many devices/providers write 0 rather than null when a photo has no
        // EXIF capture time (screenshots, some downloaded images).
        val row = DevicePhotoRow(
            mediaStoreId = 1,
            dateTakenMillis = 0L,
            dateAddedSeconds = 1_600_000_000L,
            bucketDisplayName = "Camera",
        )
        assertEquals(1_600_000_000_000L, row.resolvedTakenAtMillis())
    }

    @Test
    fun `app export bucket is recognized against CaptureRepository's own constant`() {
        // Asserting against the constant, not a hardcoded "감도" literal, so this
        // test follows CaptureRepository.GALLERY_BUCKET_NAME if it ever moves —
        // a literal here would keep passing even if the two silently drifted apart.
        assertTrue(isAppExportBucket(CaptureRepository.GALLERY_BUCKET_NAME))
        assertFalse(isAppExportBucket("Camera"))
        assertFalse(isAppExportBucket(null))
    }

    @Test
    fun `excludingAppExports drops rows the app itself wrote to MediaStore`() {
        // CaptureRepository.exportToGallery (data/CaptureRepository.kt) writes
        // finished shots to RELATIVE_PATH Pictures/{GALLERY_BUCKET_NAME}. Without
        // this filter every app capture that successfully exported would appear
        // twice in the grid: once via the `captures` table, once via this
        // MediaStore query.
        val ownExport = DevicePhotoRow(1, 100L, 100L, CaptureRepository.GALLERY_BUCKET_NAME)
        val deviceShot = DevicePhotoRow(2, 200L, 200L, "Camera")
        val result = listOf(ownExport, deviceShot).excludingAppExports()
        assertEquals(listOf(deviceShot), result)
    }

    @Test
    fun `toAlbumEntry carries only id and resolved timestamp`() {
        val row = DevicePhotoRow(mediaStoreId = 42, dateTakenMillis = 999L, dateAddedSeconds = 1L, bucketDisplayName = "Camera")
        assertEquals(AlbumEntry.DevicePhoto(mediaStoreId = 42, takenAtMillis = 999L), row.toAlbumEntry())
    }
}
