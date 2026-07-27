package com.gamdo.app.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * JVM coverage for `GeometryPlan.toAffineMatrixValues()` — the nine floats that turn
 * the whole geometry stage into one `Canvas.drawBitmap` call.
 *
 * This is the seam where a pure plan becomes an Android draw, so it is the last point
 * at which a mistake is still catchable without a device. A wrong sign here shows up
 * on a phone as a photo rotated the wrong way, which is precisely the §4-1 failure
 * mode nobody can currently observe.
 */
class RenderMatrixTest {

    private fun planAt(
        width: Int = 1000,
        height: Int = 1000,
        tiltDeg: Float = 0f,
        aspect: EditAspect = EditAspect.RATIO_1_1,
    ) = planGeometry(width, height, tiltDeg, aspect)

    @Test
    fun `an untouched frame produces the identity`() {
        val values = planAt().toAffineMatrixValues()
        val expected = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        for (i in expected.indices) {
            assertEquals("index $i", expected[i], values[i], 1e-4f)
        }
    }

    @Test
    fun `a pure crop is a translation by the crop origin`() {
        // 4:5 out of a square trims the sides, so the crop starts at x = 100.
        val plan = planAt(aspect = EditAspect.RATIO_4_5)
        assertEquals(100, plan.crop.x)

        val values = plan.toAffineMatrixValues()
        val (x, y) = mapAffinePoint(values, plan.crop.x.toFloat(), plan.crop.y.toFloat())
        assertEquals(0f, x, 1e-3f)
        assertEquals(0f, y, 1e-3f)
    }

    @Test
    fun `the last row stays affine`() {
        val values = planAt(tiltDeg = 7f).toAffineMatrixValues()
        assertEquals(0f, values[6], 1e-6f)
        assertEquals(0f, values[7], 1e-6f)
        assertEquals(1f, values[8], 1e-6f)
    }

    @Test
    fun `levelling rotates opposite the measured tilt`() {
        // A frame tilted +6 degrees must be rotated -6 to come back level.
        val plan = planAt(tiltDeg = 6f)
        assertEquals(-6f, plan.rotationDeg, 1e-4f)

        val values = plan.toAffineMatrixValues()
        // A point directly right of centre should move *up* under a counter-clockwise
        // rotation in screen coordinates (y grows downward).
        val centre = mapAffinePoint(values, 500f, 500f)
        val right = mapAffinePoint(values, 900f, 500f)
        assertTrue(
            "expected the right-hand point to rise, centre=${centre.second} right=${right.second}",
            right.second < centre.second,
        )
        assertTrue(right.first > centre.first)
    }

    @Test
    fun `rotation preserves distances`() {
        val values = planAt(tiltDeg = 9f).toAffineMatrixValues()
        val a = mapAffinePoint(values, 100f, 100f)
        val b = mapAffinePoint(values, 400f, 500f)
        val before = kotlin.math.hypot(300.0, 400.0)
        val after = kotlin.math.hypot((b.first - a.first).toDouble(), (b.second - a.second).toDouble())
        assertEquals("levelling must not scale the image", before, after, 1e-2)
    }

    @Test
    fun `the source centre lands on the crop centre when the crop is centred`() {
        val plan = planAt(tiltDeg = 5f, aspect = EditAspect.RATIO_1_1)
        val values = plan.toAffineMatrixValues()
        val (x, y) = mapAffinePoint(values, plan.sourceWidth / 2f, plan.sourceHeight / 2f)
        assertEquals(plan.crop.width / 2f, x, 1.5f)
        assertEquals(plan.crop.height / 2f, y, 1.5f)
    }

    @Test
    fun `every output pixel is covered by source content`() {
        // The crop must sit inside the rotation-safe area, or the levelled frame shows
        // transparent corners. This walks the output rectangle's corners back through
        // the inverse mapping and checks they land inside the source.
        for (tilt in floatArrayOf(0f, 1.5f, 4f, 8f, 12f, -3f, -11f)) {
            for (aspect in EditAspect.entries) {
                val plan = planGeometry(4000, 3000, tilt, aspect)
                val values = plan.toAffineMatrixValues()
                val corners = listOf(
                    0f to 0f,
                    plan.crop.width.toFloat() to 0f,
                    0f to plan.crop.height.toFloat(),
                    plan.crop.width.toFloat() to plan.crop.height.toFloat(),
                )
                corners.forEach { (ox, oy) ->
                    val (sx, sy) = inverseMap(values, ox, oy)
                    assertTrue(
                        "tilt=$tilt aspect=$aspect corner ($ox,$oy) maps outside the source at ($sx,$sy)",
                        sx >= -TOLERANCE_PX && sx <= plan.sourceWidth + TOLERANCE_PX &&
                            sy >= -TOLERANCE_PX && sy <= plan.sourceHeight + TOLERANCE_PX,
                    )
                }
            }
        }
    }

    @Test
    fun `a plan scaled to a preview resolution still maps cleanly`() {
        // Preview and save run the same plan at different resolutions; the scaled
        // matrix has to keep the output inside the scaled source.
        val full = planGeometry(4000, 3000, 6f, EditAspect.RATIO_4_5)
        val preview = full.scaledTo(full.sourceWidth, 1600)
        val values = preview.toAffineMatrixValues()

        val (sx, sy) = inverseMap(values, preview.crop.width.toFloat(), preview.crop.height.toFloat())
        assertTrue(
            "scaled corner maps to ($sx,$sy), outside ${preview.sourceWidth}x${preview.sourceHeight}",
            sx <= preview.sourceWidth + TOLERANCE_PX && sy <= preview.sourceHeight + TOLERANCE_PX,
        )
    }

    @Test
    fun `the crop never exceeds the rotated canvas`() {
        for (tilt in floatArrayOf(0f, 2f, 7f, 12f, -12f)) {
            for (aspect in EditAspect.entries) {
                val plan = planGeometry(4032, 3024, tilt, aspect)
                assertTrue(
                    "tilt=$tilt aspect=$aspect crop overruns width",
                    plan.crop.right <= plan.rotatedWidth,
                )
                assertTrue(
                    "tilt=$tilt aspect=$aspect crop overruns height",
                    plan.crop.bottom <= plan.rotatedHeight,
                )
            }
        }
    }

    /** Inverts the affine transform: output pixel back to source pixel. */
    private fun inverseMap(values: FloatArray, x: Float, y: Float): Pair<Float, Float> {
        val a = values[0]
        val b = values[1]
        val tx = values[2]
        val c = values[3]
        val d = values[4]
        val ty = values[5]
        val det = a * d - b * c
        check(abs(det) > 1e-6f) { "matrix is not invertible" }
        val px = x - tx
        val py = y - ty
        return ((d * px - b * py) / det) to ((a * py - c * px) / det)
    }

    private companion object {
        /** Rounding slack: the crop is integer, the rotation is not. */
        const val TOLERANCE_PX = 1.5f
    }
}
