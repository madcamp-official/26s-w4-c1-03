package com.gamdo.app.data.media

/**
 * O-11 — the album is one grid, app captures and device photos interleaved by
 * capture time, newest first. Neither input needs to already be sorted: this
 * re-sorts rather than two-pointer-merges, because the caller
 * (`ui/album/AlbumScreen.kt`) holds the *entire* captures list in memory but only
 * one page of device photos at a time, so "both inputs individually sorted" would
 * be an easy invariant to break by accident on a future edit. The combined list is
 * small (a demo-scale captures table plus a handful of loaded device-photo pages),
 * so a full re-sort on every page load is not a real cost.
 *
 * Ties (equal [AlbumEntry.takenAtMillis]) put the app capture first — arbitrary but
 * deterministic, which is what a JVM test can actually pin down; real-world ties at
 * millisecond resolution are not expected to matter to the user.
 */
fun mergeAlbumEntries(
    captures: List<AlbumEntry.AppCapture>,
    devicePhotos: List<AlbumEntry.DevicePhoto>,
): List<AlbumEntry> {
    val comparator = compareByDescending<AlbumEntry> { it.takenAtMillis }
        .thenBy { if (it is AlbumEntry.AppCapture) 0 else 1 }
    return (captures + devicePhotos).sortedWith(comparator)
}
