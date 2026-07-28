package com.gamdo.app.core

import android.media.ExifInterface
import java.io.File

/**
 * Strips every GPS EXIF tag from an image file before it is allowed to leave the
 * device (D8-5, blocker). This is a belt-and-suspenders safeguard — the server
 * also strips location data on its end, and `CaptureRepository.importFromGallery`
 * already drops the *entire* EXIF block for §4-3 rescue imports because it
 * decodes and re-encodes the bitmap rather than copying bytes. Reference images
 * picked straight from the system photo picker (§5-1) never pass through
 * `CaptureRepository`, so this is the only place GPS gets removed on that path.
 *
 * **Every upload path must call [sanitizeFile] before handing a file to
 * [com.gamdo.app.data.network.GamdoApiClient]** (`analyzeReference`/
 * `createEditJob`). There is no other gate — a call site that reads the picked
 * URI's bytes straight into a multipart request without going through here is a
 * D8-5 violation regardless of what happens elsewhere.
 *
 * The real [android.media.ExifInterface] I/O is not JVM-unit-testable in this
 * project — there is no Robolectric on the test classpath (`.claude/TEAM.md`
 * risk #2), and `CaptureRepository`'s own EXIF read has the same limitation. To
 * keep the *removal logic itself* verifiable, [stripGps] is written against the
 * tiny [ExifTagAccess] seam below instead of calling [ExifInterface] directly —
 * `ExifSanitizerTest` drives it with a fake and asserts every GPS tag is nulled
 * while every non-GPS tag survives untouched. [sanitizeFile] is the thin,
 * untested Android adapter; verifying it against a real photo with a GPS tag is
 * a DONE-DEVICE item, not DONE-JVM.
 */
object ExifSanitizer {

    /**
     * Every `TAG_GPS_*` constant [android.media.ExifInterface] defines on this
     * project's compileSdk (verified with `javap -constants` against
     * `android-35/android.jar` — the framework class has no
     * `TAG_GPS_H_POSITIONING_ERROR`; that one only exists on the separate
     * `androidx.exifinterface` artifact, which is not a dependency here).
     * "TAG_GPS* 전부" (D8-5) means exactly this list — 31 tags.
     */
    internal val GPS_TAG_NAMES: List<String> = listOf(
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_AREA_INFORMATION,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_DEST_BEARING,
        ExifInterface.TAG_GPS_DEST_BEARING_REF,
        ExifInterface.TAG_GPS_DEST_DISTANCE,
        ExifInterface.TAG_GPS_DEST_DISTANCE_REF,
        ExifInterface.TAG_GPS_DEST_LATITUDE,
        ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
        ExifInterface.TAG_GPS_DEST_LONGITUDE,
        ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
        ExifInterface.TAG_GPS_DIFFERENTIAL,
        ExifInterface.TAG_GPS_DOP,
        ExifInterface.TAG_GPS_IMG_DIRECTION,
        ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_MAP_DATUM,
        ExifInterface.TAG_GPS_MEASURE_MODE,
        ExifInterface.TAG_GPS_PROCESSING_METHOD,
        ExifInterface.TAG_GPS_SATELLITES,
        ExifInterface.TAG_GPS_SPEED,
        ExifInterface.TAG_GPS_SPEED_REF,
        ExifInterface.TAG_GPS_STATUS,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_TRACK,
        ExifInterface.TAG_GPS_TRACK_REF,
        ExifInterface.TAG_GPS_VERSION_ID,
    )

    /**
     * Strips every GPS tag from [file] in place. A no-op beyond the parse when
     * the file carries no GPS data. Call this before any image file is handed to
     * the network client — every upload path must go through here first (D8-5).
     */
    fun sanitizeFile(file: File) {
        val exif = ExifInterface(file.absolutePath)
        stripGps(PlatformExifTagAccess(exif))
    }

    /**
     * Pure removal algorithm: nulls out every tag in [GPS_TAG_NAMES] present in
     * [access], then persists once iff something actually changed. Returns the
     * number of tags removed (`0` for an image with no GPS data at all).
     */
    internal fun stripGps(access: ExifTagAccess): Int {
        var removed = 0
        for (tag in GPS_TAG_NAMES) {
            if (access.getAttribute(tag) != null) {
                access.setAttribute(tag, null)
                removed++
            }
        }
        if (removed > 0) access.saveAttributes()
        return removed
    }
}

/**
 * Minimal read/write/persist seam over EXIF tag storage. Exists solely so
 * [ExifSanitizer.stripGps] can be unit-tested with an in-memory fake instead of
 * the real [ExifInterface] — see the class doc on [ExifSanitizer] for why the
 * real class cannot run under plain `testDebugUnitTest` here.
 */
internal interface ExifTagAccess {
    fun getAttribute(tag: String): String?
    fun setAttribute(tag: String, value: String?)
    fun saveAttributes()
}

/** [ExifTagAccess] backed by the real platform [ExifInterface]. Untested on JVM. */
private class PlatformExifTagAccess(private val exif: ExifInterface) : ExifTagAccess {
    override fun getAttribute(tag: String): String? = exif.getAttribute(tag)
    override fun setAttribute(tag: String, value: String?) {
        exif.setAttribute(tag, value)
    }
    override fun saveAttributes() = exif.saveAttributes()
}
