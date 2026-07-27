package com.gamdo.app.ui.camera

import com.gamdo.app.detect.NormalizedBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** §3-3 → §4-1: analysis coordinates must land where the pixels actually are. */
class SubjectProjectionTest {

    private val pane = 1080f / 1500f // SM-G970N preview pane
    private val fourFive = 0.8f

    private fun box(l: Float, t: Float, r: Float, b: Float) =
        NormalizedBox(left = l, top = t, right = r, bottom = b)

    @Test
    fun `centre stays centred through both crops`() {
        val out = SubjectProjection.project(
            box = box(0.4f, 0.4f, 0.6f, 0.6f),
            paneRatioWtoH = pane,
            targetRatioWtoH = fourFive,
            mirror = false,
        )
        assertNotNull(out)
        assertEquals(0.5f, out!!.centerX, 1e-5f)
        assertEquals(0.5f, out.centerY, 1e-5f)
    }

    /**
     * The whole reason this class takes the pane aspect. If the two crops could be
     * collapsed into one 4:3 → 4:5 crop, the x coordinates would be untouched —
     * a direct crop to a *taller* target only removes height.
     */
    @Test
    fun `the viewport crop moves x, so the pane aspect cannot be skipped`() {
        val subject = box(0.1f, 0.4f, 0.3f, 0.6f)
        val twoStep = SubjectProjection.project(subject, pane, fourFive, mirror = false)!!
        val collapsed = SubjectProjection.project(subject, fourFive, fourFive, mirror = false)!!

        assertEquals("collapsing the crops must not reproduce the real one",
            true, kotlin.math.abs(twoStep.left - collapsed.left) > 0.01f)
        // The direct 4:3 -> 4:5 crop takes no width at all.
        assertEquals(0.1f, collapsed.left, 1e-5f)
        // The real path crops width, so an off-centre subject moves outward.
        assertTrue("width crop must push a left-of-centre box further left",
            twoStep.left < 0.1f)
    }

    /**
     * Locks the arithmetic against the one measurement taken from a real device:
     * sensor 3024x4032, pane 1080x1500, saved file 2904x3630. A box spanning the
     * full analysis width must therefore lose exactly the same fraction the file did.
     */
    @Test
    fun `matches the measured SM-G970N geometry`() {
        val keptWidthFraction = 2904f / 3024f
        val out = SubjectProjection.project(
            box = box(0f, 0.45f, 1f, 0.55f),
            paneRatioWtoH = pane,
            targetRatioWtoH = fourFive,
            mirror = false,
        )!!
        // Full-width box clips to the file's full width.
        assertEquals(0f, out.left, 1e-5f)
        assertEquals(1f, out.right, 1e-5f)

        // A box exactly as wide as the surviving region maps to the full width.
        val half = (1f - keptWidthFraction) / 2f
        val exact = SubjectProjection.project(
            box = box(half, 0.45f, 1f - half, 0.55f),
            paneRatioWtoH = pane,
            targetRatioWtoH = fourFive,
            mirror = false,
        )!!
        assertEquals(0f, exact.left, 2e-3f)
        assertEquals(1f, exact.right, 2e-3f)
    }

    @Test
    fun `subject cropped entirely away yields null, not a clamped sliver`() {
        // Hard against the left edge: the viewport crop removes that column.
        val out = SubjectProjection.project(
            box = box(0f, 0.4f, 0.005f, 0.6f),
            paneRatioWtoH = pane,
            targetRatioWtoH = fourFive,
            mirror = false,
        )
        assertNull(out)
    }

    @Test
    fun `partly cropped subject keeps its visible part`() {
        val out = SubjectProjection.project(
            box = box(0f, 0.4f, 0.5f, 0.6f),
            paneRatioWtoH = pane,
            targetRatioWtoH = fourFive,
            mirror = false,
        )!!
        assertEquals("the cut-off side clips to the file edge", 0f, out.left, 1e-5f)
        assertTrue(out.right in 0f..1f)
        assertTrue(out.right > out.left)
    }

    @Test
    fun `front lens mirrors horizontally and keeps the box ordered`() {
        val plain = SubjectProjection.project(box(0.1f, 0.3f, 0.4f, 0.7f), pane, fourFive, mirror = false)!!
        val front = SubjectProjection.project(box(0.1f, 0.3f, 0.4f, 0.7f), pane, fourFive, mirror = true)!!

        assertEquals(1f - plain.right, front.left, 1e-5f)
        assertEquals(1f - plain.left, front.right, 1e-5f)
        assertEquals(plain.top, front.top, 1e-5f)
        assertEquals(plain.bottom, front.bottom, 1e-5f)
        assertTrue("mirroring must not invert the box", front.right > front.left)
    }

    /**
     * 1:1 takes more height off a 0.72 pane than 4:5 does, so the *same* subject
     * ends up filling a larger share of the saved file. The box has to stay clear
     * of both edges or both results just clip to full height and the comparison
     * measures nothing.
     */
    @Test
    fun `square target leaves the subject filling more of the frame than 4 to 5`() {
        val subject = box(0.4f, 0.3f, 0.6f, 0.7f)
        val fourFiveOut = SubjectProjection.project(subject, pane, 0.8f, mirror = false)!!
        val squareOut = SubjectProjection.project(subject, pane, 1.0f, mirror = false)!!

        assertTrue("test is void if either result clipped", fourFiveOut.top > 0f && fourFiveOut.bottom < 1f)
        assertTrue("test is void if either result clipped", squareOut.top > 0f && squareOut.bottom < 1f)

        val fourFiveHeight = fourFiveOut.bottom - fourFiveOut.top
        val squareHeight = squareOut.bottom - squareOut.top
        assertTrue(
            "1:1 removes more height ($squareHeight vs $fourFiveHeight)",
            squareHeight > fourFiveHeight,
        )
        // Both stay centred: cropping is symmetric.
        assertEquals(0.5f, fourFiveOut.centerY, 1e-5f)
        assertEquals(0.5f, squareOut.centerY, 1e-5f)
    }

    @Test
    fun `non-finite and degenerate inputs are rejected rather than propagated`() {
        assertNull(SubjectProjection.project(null, pane, fourFive, false))
        assertNull(SubjectProjection.project(box(0.1f, 0.1f, 0.1f, 0.5f), pane, fourFive, false))
        assertNull(SubjectProjection.project(box(0.5f, 0.1f, 0.2f, 0.5f), pane, fourFive, false))
        assertNull(SubjectProjection.project(box(Float.NaN, 0.1f, 0.5f, 0.5f), pane, fourFive, false))
        assertNull(SubjectProjection.project(box(0.1f, 0.1f, 0.5f, 0.5f), 0f, fourFive, false))
        assertNull(SubjectProjection.project(box(0.1f, 0.1f, 0.5f, 0.5f), Float.NaN, fourFive, false))
        assertNull(SubjectProjection.project(box(0.1f, 0.1f, 0.5f, 0.5f), pane, 0f, false))
    }
}
