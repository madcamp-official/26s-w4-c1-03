package com.gamdo.app.ui.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TapFocusGeometryTest {
    @Test
    fun `in-window tap passes through unchanged`() {
        val point = resolveTapFocusPoint(300f, 700f, PANE_W, PANE_H, RATIO_4_5)
        assertNotNull(point)
        assertEquals(300f, point!!.x, TOL)
        assertEquals(700f, point.y, TOL)
    }

    @Test
    fun `letterbox and outside taps are rejected`() {
        assertNull(resolveTapFocusPoint(540f, 10f, PANE_W, PANE_H, RATIO_4_5))
        assertNull(resolveTapFocusPoint(540f, 1390f, PANE_W, PANE_H, RATIO_4_5))
        assertNull(resolveTapFocusPoint(-1f, 700f, PANE_W, PANE_H, RATIO_4_5))
        assertNull(resolveTapFocusPoint(PANE_W, 700f, PANE_W, PANE_H, RATIO_4_5))
    }

    @Test
    fun `aspect ratio changes letterbox boundaries`() {
        assertNotNull(resolveTapFocusPoint(540f, 100f, PANE_W, PANE_H, RATIO_4_5))
        assertNull(resolveTapFocusPoint(540f, 100f, PANE_W, PANE_H, RATIO_1_1))
        assertNull(resolveTapFocusPoint(540f, 159f, PANE_W, PANE_H, RATIO_1_1))
        assertNotNull(resolveTapFocusPoint(540f, 160f, PANE_W, PANE_H, RATIO_1_1))
        assertNull(resolveTapFocusPoint(540f, 1240f, PANE_W, PANE_H, RATIO_1_1))
    }

    @Test
    fun `non-finite and degenerate inputs are rejected`() {
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertNull(resolveTapFocusPoint(bad, 700f, PANE_W, PANE_H, RATIO_4_5))
            assertNull(resolveTapFocusPoint(540f, bad, PANE_W, PANE_H, RATIO_4_5))
        }
        assertNull(resolveTapFocusPoint(0f, 0f, 0f, PANE_H, RATIO_4_5))
        assertNull(resolveTapFocusPoint(0f, 0f, PANE_W, 0f, RATIO_4_5))
        assertNull(resolveTapFocusPoint(540f, 700f, PANE_W, PANE_H, 0f))
    }

    @Test
    fun `scene anchor maps a preview tap into normalized coordinates`() {
        val anchor = resolveTapSceneAnchor(500f, 650f, 1000f, 1000f, 1f)
        assertEquals(0.5f, anchor!!.x, 0.001f)
        assertEquals(0.65f, anchor.y, 0.001f)
    }

    @Test
    fun `scene anchor rejects letterbox taps`() {
        assertNull(resolveTapSceneAnchor(500f, 10f, 1000f, 1000f, 1.25f))
    }

    private companion object {
        const val TOL = 1e-4f
        const val PANE_W = 1080f
        const val PANE_H = 1400f
        val RATIO_4_5 = CaptureAspect.RATIO_4_5.ratioWtoH
        val RATIO_1_1 = CaptureAspect.RATIO_1_1.ratioWtoH
    }
}
