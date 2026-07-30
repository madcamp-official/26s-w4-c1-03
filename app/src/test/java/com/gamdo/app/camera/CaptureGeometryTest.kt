package com.gamdo.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * [captureGeometryFor] against the pipeline it replaces.
 *
 * Asserting the output *dimensions* would prove almost nothing — a mirror applied
 * on the wrong side of the crop, or an inverted rotation, gives identical
 * dimensions and a wrong photo. So the four original steps are replayed here as a
 * coordinate model ([referenceChain]) and the assertion is the strong one: for
 * every pixel of the output, both routes read **the same source pixel**.
 *
 * The model is deliberately a re-derivation rather than a copy — it maps output
 * coordinates backwards, where `BitmapExt` maps pixels forwards — so a shared
 * misunderstanding of the rotation direction would not cancel out.
 */
class CaptureGeometryTest {

    // ---- the pipeline being replaced, as pure coordinate arithmetic ----

    /** An image as "where does output (x, y) read from in the original buffer?". */
    private class Plane(val width: Int, val height: Int, val at: (Int, Int) -> Pair<Int, Int>)

    private fun Plane.sub(x: Int, y: Int, w: Int, h: Int) =
        Plane(w, h) { ox, oy -> at(ox + x, oy + y) }

    /** Clockwise, y-down — the direction `Matrix.postRotate` turns. */
    private fun Plane.turned(degrees: Int): Plane = when (degrees) {
        90 -> Plane(height, width) { ox, oy -> at(oy, height - 1 - ox) }
        180 -> Plane(width, height) { ox, oy -> at(width - 1 - ox, height - 1 - oy) }
        270 -> Plane(height, width) { ox, oy -> at(width - 1 - oy, ox) }
        else -> this
    }

    private fun Plane.flipped() = Plane(width, height) { ox, oy -> at(width - 1 - ox, oy) }

    /** `cropped` → `rotated` → `mirroredHorizontally` → `centerCropToRatio`. */
    private fun referenceChain(
        bufferWidth: Int,
        bufferHeight: Int,
        crop: CropRect?,
        rotationDegrees: Int,
        mirror: Boolean,
        targetRatioWtoH: Float?,
    ): Plane {
        var plane = Plane(bufferWidth, bufferHeight) { x, y -> x to y }

        if (crop != null) {
            val left = crop.left.coerceAtLeast(0)
            val top = crop.top.coerceAtLeast(0)
            val right = crop.right.coerceAtMost(bufferWidth)
            val bottom = crop.bottom.coerceAtMost(bufferHeight)
            if (right > left && bottom > top) {
                plane = plane.sub(left, top, right - left, bottom - top)
            }
        }

        plane = plane.turned(((rotationDegrees % 360) + 360) % 360)
        if (mirror) plane = plane.flipped()

        if (targetRatioWtoH != null) {
            val w = plane.width
            val h = plane.height
            plane = if (w.toFloat() / h.toFloat() > targetRatioWtoH) {
                val keep = (h * targetRatioWtoH).roundToInt().coerceAtMost(w)
                plane.sub((w - keep) / 2, 0, keep, h)
            } else {
                val keep = (w / targetRatioWtoH).roundToInt().coerceAtMost(h)
                plane.sub(0, (h - keep) / 2, w, keep)
            }
        }
        return plane
    }

    /** What `Bitmap.transformedBy` will do with the plan: sub-rect, then matrix. */
    private fun planned(bufferWidth: Int, bufferHeight: Int, plan: CaptureGeometry): Plane {
        var plane = Plane(bufferWidth, bufferHeight) { x, y -> x to y }
            .sub(plan.srcX, plan.srcY, plan.srcWidth, plan.srcHeight)
            .turned(plan.rotationDegrees)
        if (plan.mirror) plane = plane.flipped()
        return plane
    }

    private fun assertSamePixels(
        bufferWidth: Int,
        bufferHeight: Int,
        crop: CropRect? = null,
        rotationDegrees: Int = 0,
        mirror: Boolean = false,
        targetRatioWtoH: Float? = null,
    ) {
        val label = "${bufferWidth}x$bufferHeight crop=$crop rot=$rotationDegrees " +
            "mirror=$mirror ratio=$targetRatioWtoH"
        val plan = captureGeometryFor(
            bufferWidth, bufferHeight, crop, rotationDegrees, mirror, targetRatioWtoH,
        )
        val expected = referenceChain(
            bufferWidth, bufferHeight, crop, rotationDegrees, mirror, targetRatioWtoH,
        )
        val actual = planned(bufferWidth, bufferHeight, plan)

        assertEquals("$label: output width", expected.width, actual.width)
        assertEquals("$label: output height", expected.height, actual.height)
        assertEquals("$label: plan reports its own width", expected.width, plan.outWidth)
        assertEquals("$label: plan reports its own height", expected.height, plan.outHeight)

        // The plan must stay inside the buffer, or `Bitmap.createBitmap` throws
        // IllegalArgumentException on a photo the user just took.
        assertTrue("$label: srcX in range", plan.srcX >= 0)
        assertTrue("$label: srcY in range", plan.srcY >= 0)
        assertTrue("$label: right edge", plan.srcX + plan.srcWidth <= bufferWidth)
        assertTrue("$label: bottom edge", plan.srcY + plan.srcHeight <= bufferHeight)

        for (y in 0 until expected.height) {
            for (x in 0 until expected.width) {
                assertEquals("$label: pixel ($x,$y)", expected.at(x, y), actual.at(x, y))
            }
        }
    }

    // ---- the sweep ----

    @Test
    fun `every rotation and mirror combination reads the same pixels`() {
        for (rotation in listOf(0, 90, 180, 270)) {
            for (mirror in listOf(false, true)) {
                assertSamePixels(24, 32, rotationDegrees = rotation, mirror = mirror)
            }
        }
    }

    @Test
    fun `the shipped aspect ratios survive every rotation and mirror`() {
        // 4:5, 1:1 and 16:9 — the three the shutter offers (owner reversed D9-1's
        // "exactly two" on 2026-07-30). 0.5625 is the *tall* 9:16 frame; see
        // `CaptureAspect`'s KDoc for why a portrait camera means that by "16:9".
        for (ratio in listOf(0.5625f, 0.8f, 1.0f)) {
            for (rotation in listOf(0, 90, 180, 270)) {
                for (mirror in listOf(false, true)) {
                    assertSamePixels(
                        24, 32,
                        rotationDegrees = rotation,
                        mirror = mirror,
                        targetRatioWtoH = ratio,
                    )
                }
            }
        }
    }

    /**
     * The whole point of composing the steps: a viewport crop, a quarter turn, a
     * front-camera mirror and an aspect crop all at once, on sizes that do not
     * divide evenly.
     */
    @Test
    fun `a viewport crop composes with rotation, mirror and aspect crop`() {
        val crops = listOf(
            CropRect(3, 5, 21, 27),
            CropRect(0, 0, 24, 32),
            CropRect(7, 1, 23, 30),
        )
        for (crop in crops) {
            for (rotation in listOf(0, 90, 180, 270)) {
                for (mirror in listOf(false, true)) {
                    for (ratio in listOf(null, 0.5625f, 0.8f, 1.0f)) {
                        assertSamePixels(24, 32, crop, rotation, mirror, ratio)
                    }
                }
            }
        }
    }

    /**
     * Odd buffers are where an off-by-one hides. The centre crop uses integer
     * division, so when the trimmed amount is odd the surviving strip is half a
     * pixel off centre — and the old chain computed that **after** mirroring, so
     * the front camera landed on the other side.
     */
    @Test
    fun `odd sizes keep the front camera's off-centre bias on the same side`() {
        for (width in 15..19) {
            for (height in 21..25) {
                for (mirror in listOf(false, true)) {
                    for (rotation in listOf(0, 90, 180, 270)) {
                        assertSamePixels(
                            width, height,
                            rotationDegrees = rotation,
                            mirror = mirror,
                            targetRatioWtoH = 0.8f,
                        )
                    }
                }
            }
        }
    }

    /**
     * If the mirror were folded in before the centre crop instead of after, this is
     * the case that would catch it: an odd trim, mirrored, where the two orders
     * differ by exactly one pixel column.
     */
    @Test
    fun `mirroring shifts the odd-trim crop by exactly one column`() {
        // 4:5 from 17x20: keep = round(20*0.8) = 16, trim = 1 — odd.
        val notMirrored = captureGeometryFor(17, 20, targetRatioWtoH = 0.8f, mirror = false)
        val mirrored = captureGeometryFor(17, 20, targetRatioWtoH = 0.8f, mirror = true)

        assertEquals(16, notMirrored.outWidth)
        assertEquals(16, mirrored.outWidth)
        assertEquals("un-mirrored keeps the left bias", 0, notMirrored.srcX)
        assertEquals("mirrored keeps the right bias", 1, mirrored.srcX)
    }

    @Test
    fun `an even trim is unaffected by mirroring`() {
        // 4:5 from 18x20: keep = 16, trim = 2 — even, so both sides land at 1.
        assertEquals(1, captureGeometryFor(18, 20, targetRatioWtoH = 0.8f, mirror = false).srcX)
        assertEquals(1, captureGeometryFor(18, 20, targetRatioWtoH = 0.8f, mirror = true).srcX)
    }

    // ---- degenerate input, which reaches this from a driver rather than from us ----

    @Test
    fun `a crop that misses the buffer entirely is ignored, not obeyed`() {
        // `Bitmap.cropped` returns the original when the rect does not intersect.
        // Obeying it instead would mean an empty createBitmap and a lost photo.
        val plan = captureGeometryFor(24, 32, CropRect(100, 100, 120, 130))
        assertEquals(0, plan.srcX)
        assertEquals(0, plan.srcY)
        assertEquals(24, plan.srcWidth)
        assertEquals(32, plan.srcHeight)
    }

    @Test
    fun `an inverted or empty crop is ignored`() {
        for (crop in listOf(CropRect(10, 10, 10, 10), CropRect(20, 20, 5, 5))) {
            val plan = captureGeometryFor(24, 32, crop)
            assertEquals("$crop", 24, plan.srcWidth)
            assertEquals("$crop", 32, plan.srcHeight)
        }
    }

    @Test
    fun `a crop hanging off the edge is clamped, not rejected`() {
        val plan = captureGeometryFor(24, 32, CropRect(-5, -5, 30, 40))
        assertEquals(0, plan.srcX)
        assertEquals(0, plan.srcY)
        assertEquals(24, plan.srcWidth)
        assertEquals(32, plan.srcHeight)
    }

    /**
     * `centerCropToRatio` had no lower bound — a ratio extreme enough to round the
     * kept side to 0 produced an illegal `createBitmap` and crashed on a photo the
     * user had already taken. Unreachable from the two shipped ratios, but the
     * clamp costs nothing and the crash was real.
     */
    @Test
    fun `an extreme ratio clamps to one pixel instead of zero`() {
        val thin = captureGeometryFor(24, 32, targetRatioWtoH = 0.001f)
        assertTrue("width must stay positive, was ${thin.outWidth}", thin.outWidth >= 1)
        val flat = captureGeometryFor(24, 32, targetRatioWtoH = 1000f)
        assertTrue("height must stay positive, was ${flat.outHeight}", flat.outHeight >= 1)
    }

    @Test
    fun `negative and over-full rotations normalise`() {
        assertEquals(270, captureGeometryFor(24, 32, rotationDegrees = -90).rotationDegrees)
        assertEquals(90, captureGeometryFor(24, 32, rotationDegrees = 450).rotationDegrees)
        assertSamePixels(24, 32, rotationDegrees = -90)
        assertSamePixels(24, 32, rotationDegrees = 450)
    }

    @Test
    fun `a rotation that is not a quarter turn is refused rather than resampled`() {
        val thrown = runCatching { captureGeometryFor(24, 32, rotationDegrees = 45) }
        assertTrue("45 degrees must not be silently accepted", thrown.isFailure)
    }

    // ---- the no-op case, which is what stops this from costing a copy ----

    @Test
    fun `a capture needing nothing is recognised as needing nothing`() {
        val plan = captureGeometryFor(24, 32)
        assertTrue(plan.isNoOp(24, 32))
    }

    @Test
    fun `anything that changes a pixel is not a no-op`() {
        assertFalse(
            "a rotation is work",
            captureGeometryFor(24, 32, rotationDegrees = 90).isNoOp(24, 32),
        )
        assertFalse(
            "a mirror is work",
            captureGeometryFor(24, 32, mirror = true).isNoOp(24, 32),
        )
        assertFalse(
            "an aspect crop is work",
            captureGeometryFor(24, 32, targetRatioWtoH = 1.0f).isNoOp(24, 32),
        )
        assertFalse(
            "a viewport crop is work",
            captureGeometryFor(24, 32, CropRect(2, 2, 20, 30)).isNoOp(24, 32),
        )
    }

    /**
     * A 1:1 crop of a square is genuinely nothing to do, and must not be turned
     * into a full-resolution copy that produces an identical bitmap.
     */
    @Test
    fun `a square already at 1 to 1 is a no-op`() {
        assertTrue(captureGeometryFor(24, 24, targetRatioWtoH = 1.0f).isNoOp(24, 24))
    }

    /** A realistic 12MP frame, since every dimension above is deliberately tiny. */
    @Test
    fun `a 12MP portrait capture lands on the expected size`() {
        val plan = captureGeometryFor(
            bufferWidth = 4032,
            bufferHeight = 3024,
            rotationDegrees = 90,
            targetRatioWtoH = 0.8f,
        )
        // 90° turn → 3024x4032 upright; 4:5 wants 3024x3780, so height is trimmed.
        assertEquals(3024, plan.outWidth)
        assertEquals(3780, plan.outHeight)
        assertTrue(plan.srcX + plan.srcWidth <= 4032)
        assertTrue(plan.srcY + plan.srcHeight <= 3024)
    }

    /**
     * 16:9 on the same 12MP frame — and the branch flips.
     *
     * The upright frame is 3024×4032 (0.75), which is *wider* than 0.5625, so **width**
     * is trimmed here where 4:5 trimmed height. 4032 × 0.5625 = 2268 exactly, so the
     * expected file is 2268×4032 with 378 columns dropped from each side.
     *
     * Worth its own case because it is the only shipped ratio that takes that branch,
     * and because the arithmetic is exact — a rounding change would show up as an
     * off-by-one here rather than as a slightly wrong photograph.
     */
    @Test
    fun `a 12MP 16 to 9 capture trims width, not height`() {
        val plan = captureGeometryFor(
            bufferWidth = 4032,
            bufferHeight = 3024,
            rotationDegrees = 90,
            targetRatioWtoH = 0.5625f,
        )
        assertEquals(2268, plan.outWidth)
        assertEquals(4032, plan.outHeight)
        assertEquals(
            "the saved file must be exactly 9:16",
            0.5625f,
            plan.outWidth.toFloat() / plan.outHeight.toFloat(),
            1e-6f,
        )
        assertTrue(plan.srcX + plan.srcWidth <= 4032)
        assertTrue(plan.srcY + plan.srcHeight <= 3024)
    }

    /** The front lens takes the same path; only the mirror's odd-trim bias differs. */
    @Test
    fun `a front 16 to 9 capture is the same shape`() {
        val plan = captureGeometryFor(
            bufferWidth = 4032,
            bufferHeight = 3024,
            rotationDegrees = 90,
            mirror = true,
            targetRatioWtoH = 0.5625f,
        )
        assertEquals(2268, plan.outWidth)
        assertEquals(4032, plan.outHeight)
    }
}
