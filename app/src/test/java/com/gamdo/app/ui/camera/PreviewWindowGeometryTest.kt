package com.gamdo.app.ui.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the capture ratio's window sits in the pane — the one answer the mask, the
 * focus rule and the lasso clamp all take.
 *
 * These tests exist because 16:9 exposed a latent bug the previous two ratios could
 * never reach: the old rule could only trim **height**, so a target narrower than the
 * pane produced no bars at all and the preview showed a wider frame than the file. See
 * [previewWindowOf]'s KDoc.
 */
class PreviewWindowGeometryTest {

    // A pane roughly the shape the camera screen leaves after the top bar and the
    // shutter row: 1080 wide, 1700 tall (0.635).
    private val paneW = 1080f
    private val paneH = 1700f

    private fun window(ratio: Float) = previewWindowOf(paneW, paneH, ratio)!!

    // ---- the shape the window actually takes ------------------------------------

    @Test
    fun `4 to 5 letterboxes — height is trimmed, width is full`() {
        val w = window(0.8f)
        assertEquals(1080f, w.width, 0.01f)
        assertEquals(1350f, w.height, 0.01f)
        assertEquals("no side bars", 0f, w.left, 0.01f)
        assertEquals(175f, w.top, 0.01f)
    }

    @Test
    fun `1 to 1 letterboxes too`() {
        val w = window(1f)
        assertEquals(1080f, w.width, 0.01f)
        assertEquals(1080f, w.height, 0.01f)
        assertEquals(0f, w.left, 0.01f)
        assertEquals(310f, w.top, 0.01f)
    }

    /**
     * The case the old arithmetic could not express. 1080/0.5625 = 1920 > 1700, so the
     * old `coerceAtMost` clamped to 1700, reported `barHeight = 0`, and displayed a
     * 0.6353 window for a 0.5625 capture.
     */
    @Test
    fun `16 to 9 pillarboxes — width is trimmed, height is full`() {
        val w = window(0.5625f)
        assertEquals("height binds", 1700f, w.height, 0.01f)
        assertEquals(956.25f, w.width, 0.01f)
        assertEquals("thin side bars", 61.875f, w.left, 0.01f)
        assertEquals("no top bar", 0f, w.top, 0.01f)
    }

    /** The whole point: what is shown is the ratio that will be saved. */
    @Test
    fun `the window always has the target ratio`() {
        for (ratio in listOf(0.5625f, 0.8f, 1f)) {
            val w = window(ratio)
            assertEquals(
                "a $ratio capture must be previewed at $ratio",
                ratio,
                w.width / w.height,
                1e-4f,
            )
        }
    }

    @Test
    fun `the window is centred and inside the pane on both axes`() {
        for (ratio in listOf(0.5625f, 0.8f, 1f, 2f, 0.3f)) {
            val w = previewWindowOf(paneW, paneH, ratio)!!
            assertEquals("centred horizontally", paneW - w.right, w.left, 0.01f)
            assertEquals("centred vertically", paneH - w.bottom, w.top, 0.01f)
            assertTrue("inside the pane: $w", w.left >= -0.01f && w.top >= -0.01f)
            assertTrue(w.right <= paneW + 0.01f && w.bottom <= paneH + 0.01f)
        }
    }

    /** It must fill one axis exactly, or it is not the largest fitting rectangle. */
    @Test
    fun `the window is the largest one that fits`() {
        for (ratio in listOf(0.5625f, 0.8f, 1f, 1.5f)) {
            val w = previewWindowOf(paneW, paneH, ratio)!!
            val fillsWidth = kotlin.math.abs(w.width - paneW) < 0.01f
            val fillsHeight = kotlin.math.abs(w.height - paneH) < 0.01f
            assertTrue("ratio $ratio filled neither axis: $w", fillsWidth || fillsHeight)
        }
    }

    /** A pane already at the target has no bars at all. */
    @Test
    fun `an exact pane needs no mask`() {
        val w = previewWindowOf(800f, 1000f, 0.8f)!!
        assertEquals(0f, w.left, 0.01f)
        assertEquals(0f, w.top, 0.01f)
        assertEquals(800f, w.width, 0.01f)
        assertEquals(1000f, w.height, 0.01f)
    }

    // ---- unanswerable input -----------------------------------------------------

    @Test
    fun `an unmeasured pane or a nonsense ratio gets no answer`() {
        assertNull(previewWindowOf(0f, 1700f, 0.8f))
        assertNull(previewWindowOf(1080f, 0f, 0.8f))
        assertNull(previewWindowOf(1080f, 1700f, 0f))
        assertNull(previewWindowOf(-1f, 1700f, 0.8f))
        assertNull(previewWindowOf(Float.NaN, 1700f, 0.8f))
        assertNull(previewWindowOf(1080f, Float.POSITIVE_INFINITY, 0.8f))
        assertNull(previewWindowOf(1080f, 1700f, Float.NaN))
    }

    // ---- contains / clamp / normalize -------------------------------------------

    @Test
    fun `contains is half-open, matching the mask's bar boxes`() {
        val w = window(0.8f) // top 175, height 1350 -> [175, 1525)
        assertFalse(w.contains(500f, 174f))
        assertTrue("the row at `top` is the window's first", w.contains(500f, 175f))
        assertTrue(w.contains(500f, 1524.9f))
        assertFalse("the row at `bottom` belongs to the bar", w.contains(500f, 1525f))
    }

    /** 16:9's side bars are a no-focus zone exactly like the top and bottom ones. */
    @Test
    fun `the pillarbox side bars are outside the window`() {
        val w = window(0.5625f) // left 61.875, width 956.25
        assertFalse(w.contains(30f, 800f))
        assertTrue(w.contains(62f, 800f))
        assertTrue(w.contains(1017f, 800f))
        assertFalse(w.contains(1019f, 800f))
    }

    @Test
    fun `clamp pulls a stray sample onto the nearest edge`() {
        val w = window(0.5625f)
        assertEquals(w.left to 800f, w.clamp(0f, 800f))
        assertEquals(w.right to 800f, w.clamp(9999f, 800f))
        assertEquals(500f to w.top, w.clamp(500f, -50f))
        assertEquals(500f to w.bottom, w.clamp(500f, 9999f))
    }

    @Test
    fun `clamp leaves an inside sample alone`() {
        val w = window(0.8f)
        assertEquals(500f to 800f, w.clamp(500f, 800f))
    }

    /**
     * Normalized against the **window**, not the pane. The old `resolveTapSceneAnchor`
     * divided x by `paneWidth`, which agreed only while the window was always the full
     * pane width — true for 4:5 and 1:1, false for 16:9.
     */
    @Test
    fun `normalize spans the window, not the pane`() {
        val w = window(0.5625f)
        assertEquals(0f, w.normalize(w.left, w.top).first, 1e-4f)
        assertEquals(1f, w.normalize(w.right, w.top).first, 1e-4f)
        assertEquals(0.5f, w.normalize(paneW / 2f, w.top).first, 1e-3f)
        assertEquals(1f, w.normalize(w.left, w.bottom).second, 1e-4f)
    }

    // ---- the three consumers agree ----------------------------------------------

    @Test
    fun `the focus rule rejects exactly what the mask covers, at every ratio`() {
        for (aspect in CaptureAspect.entries) {
            val w = previewWindowOf(paneW, paneH, aspect.ratioWtoH)!!
            assertNotNull(
                "${aspect.label}: the window's own corner must focus",
                resolveTapFocusPoint(w.left, w.top, paneW, paneH, aspect.ratioWtoH),
            )
            if (w.left > 1f) {
                assertNull(
                    "${aspect.label}: a tap in the side bar must not focus",
                    resolveTapFocusPoint(w.left - 1f, w.top + 10f, paneW, paneH, aspect.ratioWtoH),
                )
            }
            if (w.top > 1f) {
                assertNull(
                    "${aspect.label}: a tap in the top bar must not focus",
                    resolveTapFocusPoint(w.left + 10f, w.top - 1f, paneW, paneH, aspect.ratioWtoH),
                )
            }
        }
    }

    @Test
    fun `the lasso clamp lands inside the window at every ratio`() {
        for (aspect in CaptureAspect.entries) {
            val w = previewWindowOf(paneW, paneH, aspect.ratioWtoH)!!
            for (probe in listOf(-99f to -99f, 9999f to 9999f, -99f to 9999f)) {
                val (cx, cy) = AreaSelectPath.clampToWindow(
                    probe.first, probe.second, paneW, paneH, aspect.ratioWtoH,
                )!!
                assertTrue(
                    "${aspect.label}: clamped ($cx, $cy) must be in $w",
                    cx >= w.left - 0.01f && cx <= w.right + 0.01f &&
                        cy >= w.top - 0.01f && cy <= w.bottom + 0.01f,
                )
            }
        }
    }

    @Test
    fun `a scene anchor is available across the whole window at every ratio`() {
        for (aspect in CaptureAspect.entries) {
            val w = previewWindowOf(paneW, paneH, aspect.ratioWtoH)!!
            val anchor = resolveTapSceneAnchor(
                w.left + w.width / 2f, w.top + w.height / 2f, paneW, paneH, aspect.ratioWtoH,
            )
            assertNotNull("${aspect.label}: the window centre must anchor", anchor)
            assertEquals("${aspect.label} centre x", 0.5f, anchor!!.x, 1e-3f)
            assertEquals("${aspect.label} centre y", 0.5f, anchor.y, 1e-3f)
        }
    }
}
