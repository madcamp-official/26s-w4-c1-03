package com.gamdo.app.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM coverage for the §4-1 resolution fallback and the memory budget.
 *
 * This is the file that matters most while no device is attached: the rule
 * "초과 시 처리 해상도 2000px로 낮추고 저장 시 원본 해상도 재적용" cannot be observed
 * on a phone, so the only evidence that the path exists and behaves is here.
 */
class RenderBudgetTest {

    /** Comfortable heap — takes the memory guard out of the picture. */
    private val roomyHeap = 512L * 1024 * 1024

    @Test
    fun `a fast render stays at full resolution`() {
        val budget = planRenderBudget(
            sourceWidth = 4000,
            sourceHeight = 3000,
            availableBytes = roomyHeap,
            lastRenderMs = 900,
        )
        assertEquals(FULL_MAX_SIDE, budget.workingMaxSide)
        assertFalse(budget.downgraded)
    }

    @Test
    fun `missing the 2s budget drops the preview to 2000px`() {
        val budget = planRenderBudget(
            sourceWidth = 4000,
            sourceHeight = 3000,
            availableBytes = roomyHeap,
            lastRenderMs = RENDER_BUDGET_MS + 1,
        )
        assertEquals(PREVIEW_MAX_SIDE, budget.workingMaxSide)
        assertTrue(budget.downgraded)
    }

    @Test
    fun `exactly on budget is not over budget`() {
        val budget = planRenderBudget(
            sourceWidth = 4000,
            sourceHeight = 3000,
            availableBytes = roomyHeap,
            lastRenderMs = RENDER_BUDGET_MS,
        )
        assertEquals(FULL_MAX_SIDE, budget.workingMaxSide)
    }

    @Test
    fun `saving re-applies the original resolution after a preview downgrade`() {
        // §4-1: "저장 시 원본 해상도 재적용". The slow preview must not follow the
        // user into the saved file.
        val preview = planRenderBudget(
            sourceWidth = 4000,
            sourceHeight = 3000,
            availableBytes = roomyHeap,
            lastRenderMs = 5_000,
        )
        val save = planRenderBudget(
            sourceWidth = 4000,
            sourceHeight = 3000,
            availableBytes = roomyHeap,
            forSave = true,
            lastRenderMs = 5_000,
        )
        assertEquals(PREVIEW_MAX_SIDE, preview.workingMaxSide)
        assertEquals(FULL_MAX_SIDE, save.workingMaxSide)
        assertFalse(save.downgraded)
    }

    @Test
    fun `a save still refuses a resolution the heap cannot hold`() {
        // A slow save beats an OutOfMemoryError; forSave waives the *time* fallback
        // only.
        val save = planRenderBudget(
            sourceWidth = 4000,
            sourceHeight = 3000,
            availableBytes = 24L * 1024 * 1024,
            forSave = true,
        )
        assertTrue(save.workingMaxSide < FULL_MAX_SIDE)
        assertTrue(save.estimatedBytes <= (24L * 1024 * 1024 * HEAP_HEADROOM).toLong())
    }

    @Test
    fun `a tight heap walks the ladder down`() {
        val tight = planRenderBudget(
            sourceWidth = 4000,
            sourceHeight = 3000,
            availableBytes = 40L * 1024 * 1024,
        )
        assertTrue(
            "expected a rung below full, got ${tight.workingMaxSide}",
            tight.workingMaxSide < FULL_MAX_SIDE,
        )
        assertTrue(RESOLUTION_LADDER.contains(tight.workingMaxSide))
    }

    @Test
    fun `an impossible heap stops at the bottom rung instead of looping`() {
        val budget = planRenderBudget(
            sourceWidth = 4000,
            sourceHeight = 3000,
            availableBytes = 1L,
        )
        assertEquals(EMERGENCY_MAX_SIDE, budget.workingMaxSide)
    }

    @Test
    fun `a small photo is never upscaled`() {
        val budget = planRenderBudget(
            sourceWidth = 1080,
            sourceHeight = 1440,
            availableBytes = roomyHeap,
        )
        assertEquals(1440, budget.workingMaxSide)
        assertEquals(1080, budget.workingWidth)
        assertEquals(1440, budget.workingHeight)
        // Not a downgrade: nothing was given up, the source was simply small.
        assertFalse(budget.downgraded)
    }

    @Test
    fun `the time fallback still bites below 2000px`() {
        // A preview already capped at 1600 would make the literal 4000 -> 2000 rule a
        // no-op; it takes one more rung instead.
        val budget = planRenderBudget(
            sourceWidth = 4000,
            sourceHeight = 3000,
            availableBytes = roomyHeap,
            requestedMaxSide = 1600,
            lastRenderMs = 4_000,
        )
        assertEquals(EMERGENCY_MAX_SIDE, budget.workingMaxSide)
    }

    @Test
    fun `the fallback is bounded at the bottom of the ladder`() {
        val budget = planRenderBudget(
            sourceWidth = 4000,
            sourceHeight = 3000,
            availableBytes = roomyHeap,
            requestedMaxSide = EMERGENCY_MAX_SIDE,
            lastRenderMs = 60_000,
        )
        assertEquals(EMERGENCY_MAX_SIDE, budget.workingMaxSide)
    }

    @Test
    fun `ladder rungs descend and terminate`() {
        assertEquals(PREVIEW_MAX_SIDE, nextRungBelow(FULL_MAX_SIDE))
        assertEquals(EMERGENCY_MAX_SIDE, nextRungBelow(PREVIEW_MAX_SIDE))
        assertNull(nextRungBelow(EMERGENCY_MAX_SIDE))
        assertNull(nextRungBelow(1))
    }

    @Test
    fun `scaling to a max side preserves the aspect ratio`() {
        val (w, h) = scaledSizeForMaxSide(4000, 3000, 2000)
        assertEquals(2000, w)
        assertEquals(1500, h)
    }

    @Test
    fun `scaling never returns a zero dimension`() {
        val (w, h) = scaledSizeForMaxSide(4000, 3, 100)
        assertTrue(w >= 1 && h >= 1)
    }

    @Test
    fun `inSampleSize never undershoots the target`() {
        // Undershooting cannot be undone, so the decoder must stop one step early.
        assertEquals(2, inSampleSizeFor(4000, 3000, 2000))
        assertEquals(4, inSampleSizeFor(4000, 3000, 1000))
        assertEquals(1, inSampleSizeFor(1600, 1200, 1600))
        assertEquals(1, inSampleSizeFor(800, 600, 4000))
    }

    @Test
    fun `inSampleSize is a power of two`() {
        for (maxSide in intArrayOf(100, 333, 512, 1000, 1600, 4000)) {
            val sample = inSampleSizeFor(4032, 3024, maxSide)
            assertEquals(
                "inSampleSize $sample for maxSide $maxSide is not a power of two",
                0,
                sample and (sample - 1),
            )
        }
    }

    @Test
    fun `bands cover every row exactly once`() {
        val width = 4000
        val height = 3000
        val plan = planBands(width, height)
        var covered = 0
        for (index in 0 until plan.bandCount) {
            covered += plan.rowsIn(index, height)
        }
        assertEquals(height, covered)
    }

    @Test
    fun `a band buffer stays inside the cap`() {
        val plan = planBands(4000, 3000, DEFAULT_MAX_BAND_BYTES)
        assertTrue(
            "band buffer ${plan.bufferBytes} exceeds the cap",
            plan.bufferBytes <= DEFAULT_MAX_BAND_BYTES,
        )
        // The whole point: nowhere near a full-frame 48MB IntArray.
        assertTrue(plan.bufferBytes < 4000L * 3000 * BYTES_PER_ARGB_PIXEL / 4)
    }

    @Test
    fun `a single row always fits even when it exceeds the cap`() {
        val plan = planBands(20_000, 10, maxBandBytes = 1024)
        assertEquals(1, plan.bandHeight)
        assertEquals(10, plan.bandCount)
    }

    @Test
    fun `the last band is short rather than out of bounds`() {
        val plan = planBands(1000, 1001, maxBandBytes = 1000 * BYTES_PER_ARGB_PIXEL * 100)
        assertEquals(100, plan.bandHeight)
        assertEquals(11, plan.bandCount)
        assertEquals(1, plan.rowsIn(10, 1001))
        assertEquals(0, plan.rowsIn(11, 1001))
    }

    @Test
    fun `the byte estimate drops with the working resolution`() {
        val full = estimateRenderBytes(4000, 3000, FULL_MAX_SIDE)
        val preview = estimateRenderBytes(4000, 3000, PREVIEW_MAX_SIDE)
        assertTrue("full $full should exceed preview $preview", full > preview)
    }

    @Test
    fun `the estimate skips the working copy when no downscale happens`() {
        // The renderer reuses the caller's bitmap when it is already small enough, so
        // only the output and one band are new allocations.
        val frame = 1000L * 1000 * BYTES_PER_ARGB_PIXEL
        val band = planBands(1000, 1000).bufferBytes
        assertEquals(frame + band, estimateRenderBytes(1000, 1000, FULL_MAX_SIDE))
    }

    @Test
    fun `the estimate counts the working copy when downscaling`() {
        // Downscaling means source and scaled copy are alive at the same time — the
        // term this whole budget exists to account for.
        val (w, h) = scaledSizeForMaxSide(4000, 4000, 1000)
        val frame = w.toLong() * h * BYTES_PER_ARGB_PIXEL
        val band = planBands(w, h).bufferBytes
        assertEquals(2 * frame + band, estimateRenderBytes(4000, 4000, 1000))
    }
}
