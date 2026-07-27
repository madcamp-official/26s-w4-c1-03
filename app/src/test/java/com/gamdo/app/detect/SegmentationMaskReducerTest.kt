package com.gamdo.app.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentationMaskReducerTest {
    @Test
    fun `foreground mask becomes a normalized non rectangular outline`() {
        val width = 16
        val height = 16
        val mask = FloatArray(width * height)
        for (y in 2..13) {
            val halfWidth = if (y in 5..10) 4 else 2
            for (x in (8 - halfWidth)..(8 + halfWidth)) {
                mask[y * width + x] = 0.95f
            }
        }

        val result = SegmentationMaskReducer(gridSize = 16).reduce(mask, width, height)

        assertNotNull(result)
        assertTrue(result!!.outline.size > 4)
        assertEquals(0.125f, result.bounds.top, 0.08f)
        assertEquals(0.875f, result.bounds.bottom, 0.08f)
        assertTrue(result.confidence > 0.9f)
    }

    @Test
    fun `empty mask returns no segmentation`() {
        val result = SegmentationMaskReducer().reduce(FloatArray(32 * 32), 32, 32)

        assertEquals(null, result)
    }
}
