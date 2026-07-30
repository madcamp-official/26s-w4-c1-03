package com.gamdo.app.ui.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shutter's three appearances (owner's final UI redesign, 2026-07-30). */
class ShutterVisualTest {

    @Test
    fun `the redesign's numbers are the ones compiled in`() {
        assertEquals("기본 White 92%", 0.92f, ShutterVisual.IDLE_ALPHA, 0f)
        assertEquals("촬영 중 78% 수축", 0.78f, ShutterVisual.CAPTURING_DISC_SCALE, 0f)
        assertEquals(1f, ShutterVisual.IDLE_DISC_SCALE, 0f)
    }

    @Test
    fun `idle is white and full size`() {
        assertFalse(ShutterVisual.alignedAmber(aligned = false, capturing = false))
        assertEquals(1f, ShutterVisual.discScale(capturing = false), 0f)
        assertEquals(ShutterAppearance.IDLE, ShutterVisual.describe(false, false))
    }

    @Test
    fun `a matched composition turns it amber`() {
        assertTrue(ShutterVisual.alignedAmber(aligned = true, capturing = false))
        assertEquals(ShutterAppearance.ALIGNED, ShutterVisual.describe(true, false))
    }

    @Test
    fun `a capture contracts the disc`() {
        assertEquals(0.78f, ShutterVisual.discScale(capturing = true), 0f)
        assertEquals(ShutterAppearance.CAPTURING, ShutterVisual.describe(false, true))
    }

    /**
     * The fourth combination the redesign's three-state wording leaves open, and the
     * reason colour and scale are decided separately. Pressing the shutter on a
     * matched composition is the *common* case; snapping the disc back to white for
     * the length of the capture would be the only hard cut on a screen whose entire
     * feedback vocabulary is a 200ms fade.
     */
    @Test
    fun `capturing while aligned stays amber and contracts`() {
        assertTrue(
            "the colour must not flash back to white on the press",
            ShutterVisual.alignedAmber(aligned = true, capturing = true),
        )
        assertEquals(0.78f, ShutterVisual.discScale(capturing = true), 0f)
    }

    /**
     * D2-5: `matchScore` must not reach the shipped UI "in any form (numeric,
     * percentage, gauge, **colour intensity**)". A Boolean input is what makes a
     * gradient inexpressible — there is no magnitude here to encode one from.
     */
    @Test
    fun `alignment is a threshold, never a magnitude`() {
        val answers = listOf(true, false).map { ShutterVisual.alignedAmber(it, capturing = false) }
        assertEquals("exactly two possible appearances", 2, answers.toSet().size)
    }
}
