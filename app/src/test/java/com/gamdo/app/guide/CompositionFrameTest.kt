package com.gamdo.app.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * review_report M1 — every composition bracket was 25% narrower than its preset asked for.
 *
 * The defining property is stated in **pixels**, not in normalized units, because
 * that is where the contract lives: `"4:5"` is a claim about the saved file's
 * shape. The old code compared normalized width against a pixel ratio, and the
 * existing `AlignmentEngineTest` only checked the bracket stayed inside 0..1 —
 * which a too-narrow box does comfortably. A test can only catch this if it
 * converts back to pixels.
 */
class CompositionFrameTest {

    private fun target(aspect: Float, scale: ClosedFloatingPointRange<Float> = 0.40f..0.50f) =
        StyleTarget(targetAspectRatio = aspect, subjectScaleRange = scale)

    /** Bracket pixel aspect for a frame of [frameRatioWtoH]. */
    private fun pixelAspect(t: StyleTarget, frameRatioWtoH: Float): Float {
        val h = CompositionFrame.height(t)
        val w = CompositionFrame.width(t, h, frameRatioWtoH)
        // normalized → pixels: multiply width by frame width, height by frame height.
        // Using H = 1 and W = frameRatioWtoH keeps the arithmetic exact.
        return (w * frameRatioWtoH) / (h * 1f)
    }

    @Test
    fun `a 4 to 5 target produces a 4 to 5 bracket on the 4 by 3 analysis frame`() {
        val t = target(4f / 5f)
        assertEquals(
            "the bracket's pixel aspect must be what the preset declared",
            4f / 5f,
            pixelAspect(t, CompositionFrame.ANALYSIS_RATIO_W_TO_H),
            0.001f,
        )
    }

    @Test
    fun `a 1 to 1 target produces a square bracket`() {
        assertEquals(1f, pixelAspect(target(1f), CompositionFrame.ANALYSIS_RATIO_W_TO_H), 0.001f)
    }

    /**
     * The size of the bug, stated so a regression is recognisable rather than just
     * numerically different. The missing divisor is the frame aspect, so on a 3:4
     * upright frame the old width was 0.75x the correct one.
     */
    @Test
    fun `the old formula was exactly the frame aspect too narrow`() {
        val t = target(4f / 5f)
        val h = CompositionFrame.height(t)
        val correct = CompositionFrame.width(t, h, CompositionFrame.ANALYSIS_RATIO_W_TO_H)
        val old = h * t.targetAspectRatio // what both call sites used to compute

        assertEquals(
            "the old width should be exactly frameRatio x the correct one",
            CompositionFrame.ANALYSIS_RATIO_W_TO_H,
            old / correct,
            0.001f,
        )
        assertTrue("and therefore narrower", old < correct)
    }

    /** The maths must hold on a frame shape other than the one it was tuned on. */
    @Test
    fun `the pixel aspect is independent of the frame shape`() {
        val t = target(4f / 5f)
        for (frame in listOf(3f / 4f, 9f / 16f, 1f, 4f / 3f)) {
            assertEquals(
                "frame $frame",
                4f / 5f,
                pixelAspect(t, frame),
                0.001f,
            )
        }
    }

    @Test
    fun `height comes from the middle of the subject scale range`() {
        assertEquals(0.45f, CompositionFrame.height(target(0.8f, 0.40f..0.50f)), 0.001f)
    }

    /**
     * The clamp is a ceiling on how much frame the guide may claim, and it is
     * allowed to break the aspect. Stated explicitly so nobody reads the aspect
     * tests above as absolute and "fixes" the clamp away.
     */
    @Test
    fun `an extreme target is clamped rather than allowed to fill the frame`() {
        val wide = CompositionFrame.width(target(4f, 0.80f..0.90f))
        assertTrue("width stays inside the drawable band, got $wide", wide <= 0.92f)
        assertTrue(wide >= 0.12f)
    }

    @Test
    fun `a non-finite or non-positive input falls back rather than producing NaN`() {
        for (bad in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val w = CompositionFrame.width(target(bad))
            assertTrue("aspect=$bad produced $w", w.isFinite() && w in 0.12f..0.92f)
            val w2 = CompositionFrame.width(target(0.8f), frameRatioWtoH = bad)
            assertTrue("frameRatio=$bad produced $w2", w2.isFinite() && w2 in 0.12f..0.92f)
        }
    }
}
