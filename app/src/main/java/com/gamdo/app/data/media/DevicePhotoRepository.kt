package com.gamdo.app.data.media

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads [AlbumEntry.DevicePhoto] pages directly from `MediaStore.Images` — the
 * W3.5-2 replacement for `ui/album/AlbumScreen.kt`'s old one-shot
 * `capturesDao().getRecent(60)` load, which cannot scale to a device photo library.
 *
 * Everything that can be pure — row shaping, the app-export dedup filter,
 * page-boundary maths — lives in `AlbumEntry.kt` / `AlbumPager.kt` and is JVM
 * tested (`AlbumEntryTest`, `AlbumPagerTest`). This class is the thin cursor glue
 * around it and **cannot run under a JVM unit test** — the stub `android.jar` used
 * for unit tests throws on any `Cursor`/`ContentResolver` method body. Verifying it
 * needs a real device or an instrumented/Robolectric test, neither of which this
 * pass has (no attached device — see AGENTS.md §8).
 *
 * Callers must already hold at least one of [com.gamdo.app.core.AppPermissions.mediaReadAlternatives];
 * `PermissionGate` (`ui/GamdoApp.kt`) enforces that for the whole app before any
 * screen — including the album — is reachable, so this class does not re-check.
 */
class DevicePhotoRepository(private val context: Context) {

    /** One page's worth of device photos, plus whether another page might exist. */
    data class DevicePhotoPage(val entries: List<AlbumEntry.DevicePhoto>, val hasMore: Boolean)

    /**
     * Loads one page of device photos, newest-taken-first, with the app's own
     * MediaStore export mirror already excluded (see [isAppExportBucket]).
     */
    suspend fun loadPage(request: PhotoPageRequest): DevicePhotoPage = withContext(Dispatchers.IO) {
        val rows = mutableListOf<DevicePhotoRow>()
        query(context.contentResolver, request)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dateTakenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                rows += cursor.toDevicePhotoRow(idCol, dateTakenCol, dateAddedCol, bucketCol)
            }
        }
        DevicePhotoPage(
            entries = rows.excludingAppExports().map { it.toAlbumEntry() },
            // Based on the *raw* row count, before the export filter: whether the DB
            // has more rows beyond this window is independent of how many of the
            // returned rows this app chooses to display.
            hasMore = hasMorePages(returnedCount = rows.size, limit = request.limit),
        )
    }

    private fun Cursor.toDevicePhotoRow(idCol: Int, dateTakenCol: Int, dateAddedCol: Int, bucketCol: Int) =
        DevicePhotoRow(
            mediaStoreId = getLong(idCol),
            dateTakenMillis = if (isNull(dateTakenCol)) null else getLong(dateTakenCol),
            dateAddedSeconds = getLong(dateAddedCol),
            bucketDisplayName = getString(bucketCol),
        )

    /**
     * API 30+ uses the documented Bundle query-args form
     * ([ContentResolver.QUERY_ARG_LIMIT] / `..._OFFSET`, both added in API 30 — the
     * Bundle overload itself exists since API 26, but not those two args).
     *
     * Below API 30 — only API 29 on this app's minSdk 29 floor — there is no Bundle
     * LIMIT/OFFSET, so this falls back to the long-standing (undocumented, but
     * still honoured by every MediaProvider implementation) trick of appending
     * `"LIMIT n OFFSET m"` to the `sortOrder` string of the legacy 5-arg
     * [ContentResolver.query]. **Neither branch has been run against a real
     * MediaStore on this pass** — this device is API 31, so only the Bundle branch
     * is even reachable here, and it has not been exercised on hardware either (no
     * attached device this session — see AGENTS.md §8).
     */
    private fun query(resolver: ContentResolver, request: PhotoPageRequest): Cursor? {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val args = Bundle().apply {
                putStringArray(
                    ContentResolver.QUERY_ARG_SORT_COLUMNS,
                    arrayOf(MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED),
                )
                putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
                putInt(ContentResolver.QUERY_ARG_LIMIT, request.limit)
                putInt(ContentResolver.QUERY_ARG_OFFSET, request.offset)
            }
            resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, args, null)
        } else {
            @Suppress("DEPRECATION")
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Images.Media.DATE_TAKEN} DESC, ${MediaStore.Images.Media.DATE_ADDED} DESC" +
                    " LIMIT ${request.limit} OFFSET ${request.offset}",
            )
        }
    }
}
