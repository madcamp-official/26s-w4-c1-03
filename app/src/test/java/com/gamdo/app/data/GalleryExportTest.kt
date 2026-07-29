package com.gamdo.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "What has actually been saved so far?" — the state that the app is allowed to
 * report, once the gallery export no longer happens before the caller returns.
 *
 * W2-2 fixed this class of defect earlier in the plan: the app said
 * `갤러리에 저장됨` while the MediaStore insert had been refused. Taking the export
 * off the shutter path recreates the same opportunity in a new shape, because a
 * `Boolean` that meant "we tried and failed" now also has to mean "not yet" — and
 * a flag that means two things is how the first one happened.
 *
 * So it is not a Boolean. These tests pin the one property that matters: of every
 * state the export can be in, **exactly one** is allowed to count as being in the
 * user's gallery.
 */
class GalleryExportTest {

    @Test
    fun `exactly one state counts as being in the gallery`() {
        // Written as a sweep over the whole enum rather than four assertions on
        // purpose: adding a fifth state later (a retry, a queue) fails this test
        // until someone decides, explicitly, which side of the line it is on.
        val inGallery = GalleryExport.entries.filter { it.isInGallery }

        assertEquals(listOf(GalleryExport.DONE), inGallery)
    }

    @Test
    fun `saved_to_gallery is 1 only for a finished export`() {
        assertEquals(1, GalleryExport.DONE.savedToGalleryColumn)
        assertEquals(0, GalleryExport.PENDING.savedToGalleryColumn)
        assertEquals(0, GalleryExport.REFUSED.savedToGalleryColumn)
        assertEquals(0, GalleryExport.NOT_REQUESTED.savedToGalleryColumn)
    }

    @Test
    fun `a queued export is not a failed one`() {
        // The distinction the old Boolean could not carry. `PENDING` says the row
        // is honest *now* and may become true; `REFUSED` says it never will
        // without another attempt. The album, the result screen and any future
        // retry all need to tell those apart.
        assertFalse(GalleryExport.PENDING.isInGallery)
        assertFalse(GalleryExport.REFUSED.isInGallery)
        assertTrue(GalleryExport.PENDING.isPending)
        assertFalse(GalleryExport.REFUSED.isPending)
    }

    @Test
    fun `an import that was already in the gallery is not something this app put there`() {
        // `importFromGallery` writes saved_to_gallery = 0 and always has. The
        // reason is not "the export failed" — no export was asked for.
        assertFalse(GalleryExport.NOT_REQUESTED.isInGallery)
        assertFalse(GalleryExport.NOT_REQUESTED.isPending)
    }

    @Test
    fun `settling a pending export records what actually happened`() {
        assertEquals(GalleryExport.DONE, GalleryExport.PENDING.settle(succeeded = true))
        assertEquals(GalleryExport.REFUSED, GalleryExport.PENDING.settle(succeeded = false))
    }

    @Test
    fun `a settled export cannot be re-settled by a late or duplicate callback`() {
        // The deferred export runs on a scope that outlives the screen. A second
        // completion — a retry that was already superseded, a coroutine resumed
        // twice — must not be able to turn a real gallery copy into `REFUSED`,
        // nor to claim one that was refused.
        assertEquals(GalleryExport.DONE, GalleryExport.DONE.settle(succeeded = false))
        assertEquals(GalleryExport.REFUSED, GalleryExport.REFUSED.settle(succeeded = true))
        assertEquals(GalleryExport.NOT_REQUESTED, GalleryExport.NOT_REQUESTED.settle(succeeded = true))
    }

    @Test
    fun `the state a capture is born in never claims the gallery`() {
        // saveCameraCapture returns the moment the row is written. Whatever it
        // returns at that point, the export has not finished — there is no state
        // reachable there that says it has.
        val atReturn = listOf(GalleryExport.PENDING, GalleryExport.NOT_REQUESTED)

        for (state in atReturn) {
            assertFalse("$state must not claim the gallery at return", state.isInGallery)
            assertEquals("$state must write 0", 0, state.savedToGalleryColumn)
        }
    }
}
