package com.gamdo.app.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W3.5-2 — page-boundary maths for paging `MediaStore.Images`, extracted so it can
 * be tested without a real cursor (which cannot run under a JVM test).
 */
class AlbumPagerTest {

    @Test
    fun `first page starts at offset zero with the requested limit`() {
        val page = PhotoPageRequest.first(limit = 60)
        assertEquals(0, page.offset)
        assertEquals(60, page.limit)
    }

    @Test
    fun `next page advances offset by limit and keeps the limit`() {
        val page = PhotoPageRequest(offset = 0, limit = 60).next()
        assertEquals(60, page.offset)
        assertEquals(60, page.limit)
        assertEquals(120, page.next().offset)
    }

    @Test
    fun `a full page implies more may exist`() {
        assertTrue(hasMorePages(returnedCount = 60, limit = 60))
    }

    @Test
    fun `a short page means the source is exhausted`() {
        assertFalse(hasMorePages(returnedCount = 12, limit = 60))
    }

    @Test
    fun `an empty page means exhausted`() {
        assertFalse(hasMorePages(returnedCount = 0, limit = 60))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative offset is rejected`() {
        PhotoPageRequest(offset = -1, limit = 60)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non-positive limit is rejected`() {
        PhotoPageRequest(offset = 0, limit = 0)
    }
}
