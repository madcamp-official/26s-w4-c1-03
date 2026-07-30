package com.gamdo.app.ui.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The capture ratios: **4:5, 1:1, 16:9** — three, cycled by one tappable label.
 *
 * D9-1 said "exactly two, do not add 16:9" and **the owner reversed it** on
 * 2026-07-30 ("4:5 1:1 16:9 비율을 버튼을 클릭해서 바꿀수 있게해"). The membership is
 * asserted rather than left as a comment because three separate things read it: the
 * top bar's cycle, `captureGeometryFor`'s crop, and — through
 * [com.gamdo.app.edit.EditAspect.nearest] — the editor's recovery of a saved photo's
 * ratio. A fourth member should be a failing test, not a silently different control.
 */
class CaptureAspectTest {

    @Test
    fun `there are exactly three ratios, in cycle order`() {
        assertEquals(
            "4:5 → 1:1 → 16:9. Adding a member changes the top bar's cycle and the " +
                "editor's `nearest` — do it deliberately, not as a side effect.",
            listOf(CaptureAspect.RATIO_4_5, CaptureAspect.RATIO_1_1, CaptureAspect.RATIO_16_9),
            CaptureAspect.entries.toList(),
        )
    }

    @Test
    fun `the ratios and labels are the ones the shutter offers`() {
        assertEquals(0.8f, CaptureAspect.RATIO_4_5.ratioWtoH, 1e-6f)
        assertEquals(1f, CaptureAspect.RATIO_1_1.ratioWtoH, 0f)
        assertEquals(0.5625f, CaptureAspect.RATIO_16_9.ratioWtoH, 1e-6f)
        assertEquals("4:5", CaptureAspect.RATIO_4_5.label)
        assertEquals("1:1", CaptureAspect.RATIO_1_1.label)
        assertEquals("16:9", CaptureAspect.RATIO_16_9.label)
    }

    /**
     * The camera is portrait-only, so the third rung continues **downward**: 16:9 here
     * is the tall 9:16 frame, the shape of the phone screen. A 1.778 landscape frame
     * would be the only wide option in a portrait app and would not match the preview.
     */
    @Test
    fun `16 to 9 is the tall frame, not the wide one`() {
        assertTrue(
            "0.5625, not 1.778 — see CaptureAspect's KDoc",
            CaptureAspect.RATIO_16_9.ratioWtoH < 1f,
        )
        assertEquals(
            "every ratio is at-or-taller than square",
            emptyList<CaptureAspect>(),
            CaptureAspect.entries.filter { it.ratioWtoH > 1f },
        )
    }

    @Test
    fun `tapping the label cycles through all three`() {
        assertEquals(CaptureAspect.RATIO_1_1, CaptureAspect.RATIO_4_5.toggled())
        assertEquals(CaptureAspect.RATIO_16_9, CaptureAspect.RATIO_1_1.toggled())
        assertEquals(CaptureAspect.RATIO_4_5, CaptureAspect.RATIO_16_9.toggled())
    }

    @Test
    fun `the cycle reaches every ratio and returns, from any start`() {
        for (start in CaptureAspect.entries) {
            val seen = mutableListOf(start)
            var current = start
            repeat(CaptureAspect.entries.size - 1) {
                current = current.toggled()
                seen += current
            }
            assertEquals(
                "starting at $start, the cycle must visit each ratio once",
                CaptureAspect.entries.size,
                seen.toSet().size,
            )
            assertEquals("and come back", start, current.toggled())
        }
    }

    /**
     * The camera's ratios and the editor's must stay the same set, or reopening a photo
     * re-crops it to whichever one `EditAspect.nearest` settles on. That is exactly the
     * failure the owner's reversal would have caused had only one enum grown.
     */
    @Test
    fun `the editor offers the same ratios as the shutter`() {
        assertEquals(
            CaptureAspect.entries.map { it.label }.toSet(),
            com.gamdo.app.edit.EditAspect.entries.map { it.presetKey }.toSet(),
        )
        for (capture in CaptureAspect.entries) {
            val edit = com.gamdo.app.edit.EditAspect.entries.first { it.presetKey == capture.label }
            assertEquals(
                "${capture.label} must mean the same shape on both sides",
                capture.ratioWtoH,
                edit.ratioWtoH,
                1e-6f,
            )
        }
    }
}
