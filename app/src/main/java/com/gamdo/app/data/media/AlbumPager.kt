package com.gamdo.app.data.media

/**
 * The default page size `DevicePhotoRepository.loadPage` and `ui/album/AlbumScreen.kt`
 * use, matching the previous one-shot `capturesDao().getRecent(60)` call's limit so
 * the first screenful of the merged grid is comparable in size to before.
 */
const val DEFAULT_ALBUM_PAGE_SIZE = 60

/**
 * One page request against `MediaStore.Images` — offset/limit only, so it can be
 * turned into either a Bundle query-args page (API 30+) or the legacy
 * `sortOrder + " LIMIT n OFFSET m"` suffix (API 29, which predates the Bundle query
 * overload) by [DevicePhotoRepository]. Pure Kotlin: the page-boundary maths does
 * not need a cursor to be exercised.
 */
data class PhotoPageRequest(val offset: Int, val limit: Int) {
    init {
        require(offset >= 0) { "offset must be >= 0, was $offset" }
        require(limit > 0) { "limit must be > 0, was $limit" }
    }

    /** The next page after this one, same size, advanced by [limit] rows. */
    fun next(): PhotoPageRequest = copy(offset = offset + limit)

    companion object {
        fun first(limit: Int = DEFAULT_ALBUM_PAGE_SIZE): PhotoPageRequest = PhotoPageRequest(offset = 0, limit = limit)
    }
}

/**
 * Whether the grid should ask for another page after a query returned
 * [returnedCount] rows for a page of size [limit].
 *
 * MediaStore does not report a total row count cheaply, so this uses the standard
 * heuristic: a page that came back full might not be the last one (ask again), a
 * short or empty page proves the source is exhausted (stop). This can issue one
 * extra empty request when the total happens to be an exact multiple of [limit] —
 * accepted; it is a single cheap query, not a correctness bug.
 */
fun hasMorePages(returnedCount: Int, limit: Int): Boolean = returnedCount >= limit
