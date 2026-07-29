package com.gamdo.app.data.media

import com.gamdo.app.data.CaptureRepository

/**
 * One tile in the album grid (O-11: app captures and device photos share a single
 * grid, ordered by capture time — no tabs, no app-captures-only mode).
 *
 * Deliberately pure Kotlin — no `android.*` types (no [android.net.Uri], no
 * [android.database.Cursor] row). The Android-facing repositories
 * ([DevicePhotoRepository] for device photos; `CapturesDao` + a small mapper in
 * `ui/album/AlbumScreen.kt` for app captures) build these from whatever
 * platform-specific source they read, so the ordering/merge/paging logic here can
 * run under a plain JVM test.
 */
sealed interface AlbumEntry {
    /** Epoch millis this photo was taken (or, failing that, added) — the sole sort key. */
    val takenAtMillis: Long

    /**
     * A row from the app's own `captures` table. Tapping one already has a working
     * destination: `Routes.result(captureId)` (see `ui/navigation/GamdoNavHost.kt`).
     */
    data class AppCapture(
        val captureId: String,
        val filePath: String,
        override val takenAtMillis: Long,
    ) : AlbumEntry

    /**
     * A photo read directly from `MediaStore.Images`, with no `captures` row behind
     * it. Tapping one has **no** working destination yet — see
     * `ui/album/AlbumScreen.kt`'s `onOpenDevicePhoto` callback and this task's report
     * on the exact seam the result screen/nav route would need.
     *
     * Carries only the MediaStore row id, not a [android.net.Uri] — building the
     * content [android.net.Uri] needs `ContentUris.withAppendedId`, which is
     * Android-only and belongs in the UI layer that actually launches something with
     * it, not in this pure data class.
     */
    data class DevicePhoto(
        val mediaStoreId: Long,
        override val takenAtMillis: Long,
    ) : AlbumEntry
}

/**
 * The shape of one `MediaStore.Images` row, exactly as read off the cursor by
 * [DevicePhotoRepository] — pure Kotlin so the shaping logic below it (timestamp
 * resolution, the app's-own-export filter) can be tested without a real cursor.
 */
data class DevicePhotoRow(
    val mediaStoreId: Long,
    /** `MediaStore.Images.Media.DATE_TAKEN`, millis. Frequently null or 0 (§ below). */
    val dateTakenMillis: Long?,
    /** `MediaStore.Images.Media.DATE_ADDED`, **seconds** (MediaStore's unit, not millis). */
    val dateAddedSeconds: Long,
    /** `MediaStore.Images.Media.BUCKET_DISPLAY_NAME` — the containing folder's name. */
    val bucketDisplayName: String?,
)

/**
 * `DATE_TAKEN` is EXIF-derived and commonly absent — screenshots, many downloaded
 * images, and some camera apps never populate it, and providers that do sometimes
 * write `0` rather than leaving the column null. `DATE_ADDED` (when MediaStore
 * indexed the file) always exists, so it is the fallback rather than a hard
 * requirement on `DATE_TAKEN`.
 */
fun DevicePhotoRow.resolvedTakenAtMillis(): Long {
    val taken = dateTakenMillis
    return if (taken != null && taken > 0) taken else dateAddedSeconds * 1000
}

/**
 * Whether a MediaStore row lives in the app's own export folder ([CaptureRepository.GALLERY_BUCKET_NAME])
 * — i.e. is really an app capture the export path already copied into MediaStore,
 * not an independent device photo.
 *
 * This exists to prevent double-counting, not to distinguish "kinds" for display:
 * every capture that `exportToGallery` successfully writes produces **two** rows
 * that would otherwise both satisfy O-11's "one grid" — the `captures` table row
 * (the one with a working `captureId` → result-screen route) and this MediaStore
 * mirror of the same JPEG bytes. Filtering the MediaStore side out here means that
 * photo renders exactly once, via its `captures` row. Device photos in any other
 * bucket are untouched.
 *
 * **Known gap, accepted for the demo (lead-reviewed):** this drops every row in the
 * bucket, not only ones that currently pair with a live `captures` row. If the
 * `captures` row for an already-exported photo is gone — the only realistic way
 * that happens today is Room being wiped (app reinstall, `Settings → 저장공간 →
 * 데이터 삭제`; `deleted_at` has no writer anywhere in the app, so soft-delete is not
 * a live path) — the exported JPEG is still on the device and still visible in any
 * other gallery app, but disappears from *this* album grid, because it is neither a
 * `captures` row (table is empty after the wipe) nor an admitted `DevicePhoto` (its
 * bucket is filtered). Accepted because: (a) the file is not lost, only hidden from
 * one app's view of it; (b) it only affects exports made *before* whatever wiped
 * Room, not ongoing use; (c) fixing it costs a real join — matching a MediaStore row
 * in this bucket against the current `captures` table by filename (there is no
 * MediaStore-URI column on `Captures` to join on directly, and Room is frozen so one
 * cannot be added) to re-admit only the orphaned ones, on every page load. Flagged
 * to the lead rather than built (2026-07-29 — lead confirmed: not worth building now,
 * AI 3 / reference-orientation / W4 rehearsal all rank ahead of it).
 *
 * **Cheap precondition to reopen this:** the moment anything in this app starts
 * writing `deleted_at`, the "half the scenario cannot happen" argument above stops
 * holding and this should be revisited — soft-delete plus this filter means a user
 * action (delete) would make an exported photo disappear from every gallery view
 * inside this app while a copy silently persists in `Pictures/감도`, which is a much
 * more likely and more confusing case than a reinstall.
 */
fun isAppExportBucket(bucketDisplayName: String?): Boolean = bucketDisplayName == CaptureRepository.GALLERY_BUCKET_NAME

/** See [isAppExportBucket]. */
fun List<DevicePhotoRow>.excludingAppExports(): List<DevicePhotoRow> = filterNot { isAppExportBucket(it.bucketDisplayName) }

/** Drops everything [DevicePhotoRepository] read except what the grid actually needs. */
fun DevicePhotoRow.toAlbumEntry(): AlbumEntry.DevicePhoto =
    AlbumEntry.DevicePhoto(mediaStoreId = mediaStoreId, takenAtMillis = resolvedTakenAtMillis())
