package com.gamdo.app.ui.camera

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * D9-1: the capture aspect is **4:5 and 1:1, exactly**. 16:9, 3:4 and "full" are
 * banned, and the redesign keeps the choice (the owner reversed the earlier
 * ratio-removal proposal on 2026-07-30).
 *
 * The top bar's control changed shape with the redesign — one tappable label instead
 * of a two-cell segmented chip — and that shape only makes sense while there are two
 * values. A third would turn a toggle into a cycle nobody designed, so the count is
 * asserted here rather than left as a comment.
 */
class CaptureAspectTest {

    @Test
    fun `there are exactly two ratios`() {
        assertEquals(
            "D9-1: 4:5 and 1:1 only. Adding a member turns the top bar's toggle into " +
                "a cycle — change the control deliberately, not as a side effect.",
            listOf(CaptureAspect.RATIO_4_5, CaptureAspect.RATIO_1_1),
            CaptureAspect.entries.toList(),
        )
    }

    @Test
    fun `the ratios are the ones D9 names`() {
        assertEquals(0.8f, CaptureAspect.RATIO_4_5.ratioWtoH, 1e-6f)
        assertEquals(1f, CaptureAspect.RATIO_1_1.ratioWtoH, 0f)
        assertEquals("4:5", CaptureAspect.RATIO_4_5.label)
        assertEquals("1:1", CaptureAspect.RATIO_1_1.label)
    }

    @Test
    fun `tapping the label toggles between them`() {
        assertEquals(CaptureAspect.RATIO_1_1, CaptureAspect.RATIO_4_5.toggled())
        assertEquals(CaptureAspect.RATIO_4_5, CaptureAspect.RATIO_1_1.toggled())
    }

    @Test
    fun `two taps return to where you started, from either side`() {
        for (start in CaptureAspect.entries) {
            assertEquals(start, start.toggled().toggled())
        }
    }
}
