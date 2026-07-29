package com.gamdo.app.data.media

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * W3.5-2 / O-11 — the album is one grid, app captures and device photos ordered
 * together by capture time (newest first), not tabs and not app-only. Pure Kotlin,
 * no `android.*` imports: the two input lists are whatever `CapturesDao.getRecent`
 * and [DevicePhotoRepository] already produced.
 */
class AlbumMergeTest {

    private fun capture(id: String, at: Long) =
        AlbumEntry.AppCapture(captureId = id, filePath = "/x/$id.jpg", takenAtMillis = at)

    private fun photo(id: Long, at: Long) = AlbumEntry.DevicePhoto(mediaStoreId = id, takenAtMillis = at)

    @Test
    fun `interleaves both sources by descending timestamp`() {
        val captures = listOf(capture("cap_2", 200L), capture("cap_1", 100L))
        val photos = listOf(photo(30, 300L), photo(10, 150L))

        val merged = mergeAlbumEntries(captures, photos)

        assertEquals(
            listOf(photo(30, 300L), capture("cap_2", 200L), photo(10, 150L), capture("cap_1", 100L)),
            merged,
        )
    }

    @Test
    fun `works even when an input list arrives out of order`() {
        val captures = listOf(capture("cap_1", 100L), capture("cap_2", 200L))
        assertEquals(
            listOf(capture("cap_2", 200L), capture("cap_1", 100L)),
            mergeAlbumEntries(captures, emptyList()),
        )
    }

    @Test
    fun `empty captures returns device photos only, newest first`() {
        val photos = listOf(photo(1, 50L), photo(2, 90L))
        assertEquals(
            listOf(photo(2, 90L), photo(1, 50L)),
            mergeAlbumEntries(emptyList(), photos),
        )
    }

    @Test
    fun `equal timestamps break ties deterministically, app capture first`() {
        val cap = capture("cap_1", 100L)
        val pic = photo(1, 100L)
        assertEquals(listOf(cap, pic), mergeAlbumEntries(listOf(cap), listOf(pic)))
    }

    @Test
    fun `both empty returns empty`() {
        assertEquals(emptyList<AlbumEntry>(), mergeAlbumEntries(emptyList(), emptyList()))
    }
}
