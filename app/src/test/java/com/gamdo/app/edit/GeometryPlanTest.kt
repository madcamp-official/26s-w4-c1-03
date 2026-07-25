package com.gamdo.app.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM coverage for the geometry half of §4-1 (levelling + ratio crop). */
class GeometryPlanTest {

    @Test
    fun `D9-1 only two aspect ratios exist`() {
        // Guard, not a formality: 16:9 / 3:4 / full are forbidden by D9-1.
        assertEquals(2, EditAspect.entries.size)
        assertTrue(EditAspect.entries.containsAll(listOf(EditAspect.RATIO_4_5, EditAspect.RATIO_1_1)))
    }

    @Test
    fun `preset aspect keys map to the supported ratios`() {
        assertEquals(EditAspect.RATIO_4_5, EditAspect.fromPresetKey("4:5"))
        assertEquals(EditAspect.RATIO_1_1, EditAspect.fromPresetKey("1:1"))
    }

    @Test
    fun `unknown or missing aspect keys fall back to 4 by 5`() {
        assertEquals(EditAspect.RATIO_4_5, EditAspect.fromPresetKey("16:9"))
        assertEquals(EditAspect.RATIO_4_5, EditAspect.fromPresetKey(null))
    }

    @Test
    fun `nearest ratio mirrors the camera selection`() {
        assertEquals(EditAspect.RATIO_1_1, EditAspect.nearest(0.99f))
        assertEquals(EditAspect.RATIO_4_5, EditAspect.nearest(0.81f))
    }

    @Test
    fun `tiny tilts are ignored`() {
        assertEquals(0f, levelingRotationDeg(0.1f), 1e-6f)
        assertEquals(0f, levelingRotationDeg(-0.2f), 1e-6f)
    }

    @Test
    fun `levelling rotates against the measured tilt`() {
        assertEquals(-5f, levelingRotationDeg(5f), 1e-6f)
        assertEquals(5f, levelingRotationDeg(-5f), 1e-6f)
    }

    @Test
    fun `levelling is clamped so a deliberate angle is not straightened`() {
        assertEquals(-MAX_LEVELING_DEG, levelingRotationDeg(40f), 1e-6f)
        assertEquals(MAX_LEVELING_DEG, levelingRotationDeg(-40f), 1e-6f)
    }

    @Test
    fun `levelling survives a bad sensor reading`() {
        assertEquals(0f, levelingRotationDeg(Float.NaN), 1e-6f)
    }

    @Test
    fun `rotating by zero changes no bounds`() {
        val (w, h) = rotatedBounds(1000, 800, 0f)
        assertEquals(1000, w)
        assertEquals(800, h)
    }

    @Test
    fun `rotation grows the bounding box`() {
        val (w, h) = rotatedBounds(1000, 800, 15f)
        assertTrue(w > 1000)
        assertTrue(h > 800)
    }

    @Test
    fun `unrotated frames keep their whole area`() {
        val (w, h) = largestInnerRect(1000, 800, 0f)
        assertEquals(1000f, w, 1e-3f)
        assertEquals(800f, h, 1e-3f)
    }

    @Test
    fun `rotation shrinks the usable area`() {
        val (w, h) = largestInnerRect(1000, 800, 10f)
        assertTrue("width must shrink, was $w", w < 1000f)
        assertTrue("height must shrink, was $h", h < 800f)
        assertTrue(w > 0f && h > 0f)
    }

    @Test
    fun `square source cropped square is a no-op`() {
        val plan = planGeometry(1000, 1000, 0f, EditAspect.RATIO_1_1)
        assertEquals(0f, plan.rotationDeg, 1e-6f)
        assertEquals(CropRect(0, 0, 1000, 1000), plan.crop)
        assertFalse(plan.marginExpansionCandidate)
    }

    @Test
    fun `square source cropped 4 by 5 trims the sides evenly`() {
        val plan = planGeometry(1000, 1000, 0f, EditAspect.RATIO_4_5)
        assertEquals(800, plan.crop.width)
        assertEquals(1000, plan.crop.height)
        assertEquals(100, plan.crop.x)
        assertEquals(0, plan.crop.y)
    }

    @Test
    fun `crop honours the requested ratio`() {
        val plan = planGeometry(4000, 3000, 0f, EditAspect.RATIO_4_5)
        val ratio = plan.crop.width.toFloat() / plan.crop.height
        assertEquals(EditAspect.RATIO_4_5.ratioWtoH, ratio, 0.01f)
    }

    @Test
    fun `crop follows an off-centre subject but stays in frame`() {
        val plan = planGeometry(1000, 1000, 0f, EditAspect.RATIO_4_5, subjectCenterX = 0.9f)
        assertEquals(200, plan.crop.x)
        assertTrue(plan.crop.right <= plan.rotatedWidth)
    }

    @Test
    fun `crop never leaves the rotated canvas`() {
        for (tilt in listOf(-12f, -7f, -1f, 0f, 1f, 7f, 12f)) {
            for (aspect in EditAspect.entries) {
                val plan = planGeometry(4000, 3000, tilt, aspect, subjectCenterX = 0.15f)
                assertTrue("x negative at $tilt/$aspect", plan.crop.x >= 0)
                assertTrue("y negative at $tilt/$aspect", plan.crop.y >= 0)
                assertTrue("overflows width at $tilt/$aspect", plan.crop.right <= plan.rotatedWidth)
                assertTrue("overflows height at $tilt/$aspect", plan.crop.bottom <= plan.rotatedHeight)
                assertTrue("empty crop at $tilt/$aspect", plan.crop.width > 0 && plan.crop.height > 0)
            }
        }
    }

    @Test
    fun `levelling that costs too much frame is flagged for margin expansion`() {
        // 4:3 landscape straightened by 12 degrees cannot yield a 4:5 crop without
        // losing more than half the frame — §4-1 says mark it, do not crop harder.
        val plan = planGeometry(4000, 3000, 12f, EditAspect.RATIO_4_5)
        assertTrue(plan.marginExpansionCandidate)
    }

    @Test
    fun `an unrotated frame is never a margin expansion candidate`() {
        assertFalse(planGeometry(4000, 3000, 0f, EditAspect.RATIO_4_5).marginExpansionCandidate)
    }

    @Test
    fun `plans scale between preview and full resolution`() {
        val full = planGeometry(4000, 3000, 6f, EditAspect.RATIO_4_5)
        val preview = full.scaledTo(fromWidth = 4000, toWidth = 2000)
        assertEquals(2000, preview.sourceWidth)
        assertTrue(
            "halved crop width should track the source, was ${preview.crop.width}",
            kotlin.math.abs(preview.crop.width - full.crop.width / 2) <= 2,
        )
        assertEquals(full.rotationDeg, preview.rotationDeg, 1e-6f)
    }
}
