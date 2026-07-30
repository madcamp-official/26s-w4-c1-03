package com.gamdo.app.ui.camera

import com.gamdo.app.camera.CaptureGeometry
import com.gamdo.app.camera.CropRect
import com.gamdo.app.camera.captureGeometryFor
import com.gamdo.app.detect.NormalizedBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §3-3 → §4-1: analysis coordinates must land where the pixels actually are.
 *
 * ## How this is checked
 *
 * The projection's job is to answer "the detector saw a person *here*; where is that
 * person in the saved file?", and the saved file is produced by
 * [captureGeometryFor] + `Bitmap.transformedBy`. So the check that actually proves
 * something is a **round trip through a pixel**: take a pixel of the capture buffer,
 * work out on one side where it sits in the analysis frame and on the other side
 * where it sits in the output file, and assert the projection maps the first to the
 * second. [everyKeptPixelLandsWhereTheTransformPutsIt] does that over a grid, for
 * every rotation, both lenses, all three ratios and an off-centre viewport crop.
 *
 * The rotation is re-derived here as a **pixel** map ([uprightPixel]) rather than
 * borrowed as a rect formula, because the whole class of bug being guarded against is
 * an axis or a sign — and a rect formula copied from the code under test would agree
 * with it while both were wrong.
 *
 * ## What used to be here
 *
 * Two of the tests this replaces were asserting the defect.
 *
 * `the viewport crop moves x, so the pane aspect cannot be skipped` required an
 * off-centre subject's x to move, which is exactly what the shutter does **not** do
 * on this device. And `matches the measured SM-G970N geometry` locked the arithmetic
 * to "the one measurement taken from a real device: … saved file 2904×3630" — a
 * figure that was never measured. It was the model's own prediction, written into a
 * test as evidence for itself, which is how a wrong model survives a green suite.
 * Real: 3024×3780. See [SubjectProjection]'s KDoc for the error table.
 */
class SubjectProjectionTest {

    // ---------------------------------------------------------------- device facts

    /**
     * SM-G970N rear capture, as it arrives in `onCaptureSuccess`: the sensor buffer is
     * landscape and `imageInfo.rotationDegrees` is the 90 that stands it up. Confirmed
     * by `CaptureLatency geometry` — `captureGeometryFor(4032, 3024, rotation = 90,
     * target = 0.8)` reports 3024×3780, which is what the file measured.
     */
    private val bufW = 4032
    private val bufH = 3024
    private val rot = 90

    private fun devicePlan(
        targetRatioWtoH: Float?,
        mirror: Boolean = false,
        crop: CropRect? = null,
    ) = captureGeometryFor(
        bufferWidth = bufW,
        bufferHeight = bufH,
        crop = crop,
        rotationDegrees = rot,
        mirror = mirror,
        targetRatioWtoH = targetRatioWtoH,
    )

    private fun box(l: Float, t: Float, r: Float, b: Float) =
        NormalizedBox(left = l, top = t, right = r, bottom = b)

    /** A box small enough that its centre is the only thing being compared. */
    private fun pointBox(x: Float, y: Float, e: Float = 3e-4f) =
        box(x - e, y - e, x + e, y + e)

    // ------------------------------------------------- independent coordinate model

    /**
     * Where buffer pixel (bx, by) ends up in the upright frame, for a clockwise,
     * y-down quarter turn — the same convention `Matrix.postRotate` uses.
     *
     * Stated per pixel and with the `- 1`s spelled out, so it is a description of the
     * rotation rather than a restatement of the rect arithmetic under test.
     */
    private fun uprightPixel(bx: Int, by: Int, w: Int, h: Int, rotation: Int): Pair<Int, Int> =
        when (rotation) {
            90 -> (h - 1 - by) to bx
            180 -> (w - 1 - bx) to (h - 1 - by)
            270 -> by to (w - 1 - bx)
            else -> bx to by
        }

    /** The plan's kept rect in upright pixels, from its four corners. */
    private fun uprightWindow(plan: CaptureGeometry, w: Int, h: Int): IntArray {
        val x1 = plan.srcX
        val y1 = plan.srcY
        val x2 = plan.srcX + plan.srcWidth - 1
        val y2 = plan.srcY + plan.srcHeight - 1
        val corners = listOf(x1 to y1, x2 to y1, x1 to y2, x2 to y2)
            .map { (bx, by) -> uprightPixel(bx, by, w, h, plan.rotationDegrees) }
        val xs = corners.map { it.first }
        val ys = corners.map { it.second }
        return intArrayOf(xs.min(), ys.min(), xs.max() - xs.min() + 1, ys.max() - ys.min() + 1)
    }

    // ------------------------------------------------------------------- the fix

    /**
     * The defect, at the two points it was measured at.
     *
     * The viewport takes no width on this device, so a subject's x must come through
     * **untouched**. The old two-crop inference moved it: 0.75 → 0.790 and
     * 0.90 → 0.963, an error of 4.0% and 6.3% of the frame. y is a single centre crop
     * of 126px each end, so 0.30 → 0.2867 and 0.15 → 0.1270 (the inference said
     * 0.253 and 0.068).
     *
     * If a pane-aspect term is ever reintroduced, x moves and this fails.
     */
    @Test
    fun `a 4 by 5 capture keeps x exactly, because no viewport crop happened`() {
        val plan = devicePlan(0.8f)
        assertEquals("the plan must be the measured one", 3024, plan.outWidth)
        assertEquals(3780, plan.outHeight)

        val upperRight = SubjectProjection.project(pointBox(0.75f, 0.30f), plan, bufW, bufH)!!
        assertEquals(0.75f, upperRight.centerX, 1e-4f)
        assertEquals(0.2866667f, upperRight.centerY, 1e-4f)

        val nearEdge = SubjectProjection.project(pointBox(0.90f, 0.15f), plan, bufW, bufH)!!
        assertEquals(0.90f, nearEdge.centerX, 1e-4f)
        assertEquals(0.1270f, nearEdge.centerY, 1e-3f)
    }

    /**
     * 1:1 at the edge is where the old model left the frame: it produced y = −0.041,
     * a normalized coordinate outside the photograph. The real crop puts it at 0.033,
     * inside — barely, which is the point. Nothing downstream would have rejected
     * −0.041; §4-1 would have centred a crop on it.
     */
    @Test
    fun `a square capture keeps the edge subject inside the frame`() {
        val out = SubjectProjection.project(pointBox(0.90f, 0.15f), devicePlan(1.0f), bufW, bufH)!!
        assertEquals(0.90f, out.centerX, 1e-4f)
        assertEquals(0.0333f, out.centerY, 1e-3f)
        assertTrue("must not leave the frame, was ${out.centerY}", out.centerY > 0f)
    }

    /**
     * 16:9 **does** crop the width — 0.5625 is narrower than the sensor's 0.75, so
     * the aspect crop takes 378px off each side and x moves. That is not a
     * contradiction of the test above: the difference comes from the plan, which is
     * the entire change. It also explains why 16:9 was the wrong ratio to try to see
     * the old defect with — the phantom pane crop was masked by a real, larger one,
     * and 16:9 measured 0% error while 4:5 measured 6.3%.
     */
    @Test
    fun `16 by 9 moves x and 4 by 5 does not, both read off the plan`() {
        val wide = devicePlan(0.5625f)
        assertEquals(2268, wide.outWidth)
        assertEquals(4032, wide.outHeight)

        val out = SubjectProjection.project(pointBox(0.75f, 0.30f), wide, bufW, bufH)!!
        // 378/3024 trimmed each side: (0.75 - 0.125) / 0.75.
        assertEquals(0.8333f, out.centerX, 1e-3f)
        assertEquals("full height is kept", 0.30f, out.centerY, 1e-4f)
    }

    /**
     * The thing the old signature structurally could not express. A `cropRect` is not
     * obliged to be centred — CameraX's viewport follows the `PreviewView`, and a
     * pane that is not centred in its parent produces an off-centre rect. Two aspect
     * ratios can only ever describe a symmetric crop, so the inference would have
     * placed this subject on the wrong side of the frame no matter what was passed.
     */
    @Test
    fun `an off-centre viewport crop is followed, not assumed centred`() {
        // Buffer coords, so this trims the *upright* frame's bottom (rotation 90).
        val plan = devicePlan(null, crop = CropRect(0, 0, 3200, 3024))
        val centred = devicePlan(null, crop = CropRect(416, 0, 3616, 3024))

        val off = SubjectProjection.project(pointBox(0.5f, 0.5f), plan, bufW, bufH)!!
        val mid = SubjectProjection.project(pointBox(0.5f, 0.5f), centred, bufW, bufH)!!
        assertEquals("same width kept, so the same span", off.right - off.left, mid.right - mid.left, 1e-4f)
        assertTrue(
            "an off-centre window must not project like a centred one of equal size",
            kotlin.math.abs(off.centerY - mid.centerY) > 0.02f,
        )
        // Under the 90° turn a buffer *column* becomes an upright *row*: buffer x
        // 0..3200 is the upright frame's top 0.7937, so trimming the buffer's right
        // edge removes the bottom of the photo and the analysis centre (0.5) lands
        // below the window's centre (0.3968) at 0.5 / 0.7937 = 0.63.
        //
        // Worth spelling out because the first version of this test asserted 0.3701 —
        // the same crop applied to the wrong end of the wrong axis. The grid test
        // above, whose expectation is derived per pixel, was right; this hand-written
        // number was not.
        assertEquals(0.63f, off.centerY, 1e-3f)
        assertEquals(0.5f, mid.centerY, 1e-3f)
    }

    // ----------------------------------------------------------- the round trip

    /**
     * The load-bearing test: for a grid of buffer pixels, the position the projection
     * reports must be the position `Bitmap.transformedBy` will actually read that
     * pixel into.
     *
     * Both sides are computed from [uprightPixel] — one against the full frame (which
     * is what a detector's normalized coordinate means) and one against the plan's
     * kept window (which is what the file's normalized coordinate means). The
     * projection under test never appears in either.
     */
    @Test
    fun everyKeptPixelLandsWhereTheTransformPutsIt() {
        val plans = buildList {
            for (rotation in listOf(0, 90, 180, 270)) {
                for (mirror in listOf(false, true)) {
                    for (target in listOf(null, 0.8f, 1.0f, 0.5625f)) {
                        add(
                            Triple(
                                captureGeometryFor(
                                    bufferWidth = 400,
                                    bufferHeight = 300,
                                    crop = CropRect(20, 10, 380, 290),
                                    rotationDegrees = rotation,
                                    mirror = mirror,
                                    targetRatioWtoH = target,
                                ),
                                400,
                                300,
                            ),
                        )
                    }
                }
            }
        }

        for ((plan, w, h) in plans) {
            val quarterTurn = plan.rotationDegrees == 90 || plan.rotationDegrees == 270
            val uprightW = if (quarterTurn) h else w
            val uprightH = if (quarterTurn) w else h
            val win = uprightWindow(plan, w, h)
            // The analysis frame is pinned to the buffer's own aspect here so the
            // field-of-view correction is out of the way; `anAnalysisFrameWider...`
            // covers it on its own.
            val sourceRatio = uprightW.toFloat() / uprightH.toFloat()
            val label = "rot=${plan.rotationDegrees} mirror=${plan.mirror} " +
                "out=${plan.outWidth}x${plan.outHeight}"

            // Inset so a point box cannot clip against the window edge.
            var checked = 0
            for (bx in plan.srcX + 3 until plan.srcX + plan.srcWidth - 3 step 7) {
                for (by in plan.srcY + 3 until plan.srcY + plan.srcHeight - 3 step 7) {
                    val (ux, uy) = uprightPixel(bx, by, w, h, plan.rotationDegrees)
                    // Where the detector would have reported this pixel.
                    val analysisX = (ux + 0.5f) / uprightW
                    val analysisY = (uy + 0.5f) / uprightH
                    // Where `transformedBy` puts it in the file.
                    var fileX = (ux + 0.5f - win[0]) / win[2]
                    val fileY = (uy + 0.5f - win[1]) / win[3]
                    if (plan.mirror) fileX = 1f - fileX

                    val out = SubjectProjection.project(
                        box = pointBox(analysisX, analysisY),
                        geometry = plan,
                        bufferWidth = w,
                        bufferHeight = h,
                        sourceRatioWtoH = sourceRatio,
                    )
                    assertNotNull("$label: pixel ($bx,$by) is inside the crop", out)
                    assertEquals("$label: x of ($bx,$by)", fileX, out!!.centerX, 2e-3f)
                    assertEquals("$label: y of ($bx,$by)", fileY, out.centerY, 2e-3f)
                    checked++
                }
            }
            assertTrue("$label: grid covered nothing", checked > 100)
        }
    }

    // ------------------------------------------------------------------- behaviour

    @Test
    fun `centre stays centred`() {
        for (target in listOf(null, 0.8f, 1.0f, 0.5625f)) {
            val out = SubjectProjection.project(box(0.4f, 0.4f, 0.6f, 0.6f), devicePlan(target), bufW, bufH)
            assertNotNull("target=$target", out)
            assertEquals("target=$target", 0.5f, out!!.centerX, 1e-5f)
            assertEquals("target=$target", 0.5f, out.centerY, 1e-5f)
        }
    }

    @Test
    fun `front lens mirrors horizontally and keeps the box ordered`() {
        val subject = box(0.1f, 0.3f, 0.4f, 0.7f)
        val plain = SubjectProjection.project(subject, devicePlan(0.8f, mirror = false), bufW, bufH)!!
        val front = SubjectProjection.project(subject, devicePlan(0.8f, mirror = true), bufW, bufH)!!

        assertEquals(1f - plain.right, front.left, 1e-4f)
        assertEquals(1f - plain.left, front.right, 1e-4f)
        assertEquals(plain.top, front.top, 1e-5f)
        assertEquals(plain.bottom, front.bottom, 1e-5f)
        assertTrue("mirroring must not invert the box", front.right > front.left)
    }

    @Test
    fun `subject cropped entirely away yields null, not a clamped sliver`() {
        // Hard against the top of the analysis frame; the 4:5 crop removes that band.
        val out = SubjectProjection.project(box(0.4f, 0f, 0.6f, 0.005f), devicePlan(0.8f), bufW, bufH)
        assertNull(out)
    }

    @Test
    fun `partly cropped subject keeps its visible part`() {
        val out = SubjectProjection.project(box(0.4f, 0f, 0.6f, 0.5f), devicePlan(0.8f), bufW, bufH)!!
        assertEquals("the cut-off side clips to the file edge", 0f, out.top, 1e-5f)
        assertTrue(out.bottom in 0f..1f)
        assertTrue(out.bottom > out.top)
    }

    /**
     * 1:1 takes more height off a 0.75 frame than 4:5 does, so the same subject fills
     * more of the saved file. The box has to stay clear of both edges or both results
     * clip to full height and the comparison measures nothing.
     */
    @Test
    fun `square target leaves the subject filling more of the frame than 4 to 5`() {
        val subject = box(0.4f, 0.4f, 0.6f, 0.6f)
        val fourFive = SubjectProjection.project(subject, devicePlan(0.8f), bufW, bufH)!!
        val square = SubjectProjection.project(subject, devicePlan(1.0f), bufW, bufH)!!

        assertTrue("void if clipped", fourFive.top > 0f && fourFive.bottom < 1f)
        assertTrue("void if clipped", square.top > 0f && square.bottom < 1f)
        assertTrue(
            "1:1 removes more height (${square.bottom - square.top} vs ${fourFive.bottom - fourFive.top})",
            (square.bottom - square.top) > (fourFive.bottom - fourFive.top),
        )
        assertEquals(0.5f, fourFive.centerY, 1e-5f)
        assertEquals(0.5f, square.centerY, 1e-5f)
    }

    /**
     * The one ratio term that survives, and it is a measurement of two known frames
     * rather than a prediction. If the analysis stream came back wider than the
     * capture buffer, the detector's 0..1 would span more of the world than the
     * buffer's and the box would have to be narrowed to match.
     */
    @Test
    fun `an analysis frame wider than the buffer is corrected for`() {
        val plan = devicePlan(null)
        // Buffer upright is 0.75. Claim the detector saw 1.0 — a wider frame, so its
        // x=0.625 is the buffer's x=0.75: (0.625 - 0.125) / 0.75.
        val out = SubjectProjection.project(
            box = pointBox(0.625f, 0.5f),
            geometry = plan,
            bufferWidth = bufW,
            bufferHeight = bufH,
            sourceRatioWtoH = 1.0f,
        )!!
        assertEquals(0.6667f, out.centerX, 1e-3f)
        // And with no correction it would have stayed put.
        val uncorrected = SubjectProjection.project(pointBox(0.625f, 0.5f), plan, bufW, bufH)!!
        assertEquals(0.625f, uncorrected.centerX, 1e-3f)
    }

    /**
     * A plan paired with the wrong buffer size is a caller bug — one capture's
     * geometry against another's dimensions — and there is no meaningful projection
     * through it. Rejecting beats silently rescaling into a plausible box.
     */
    @Test
    fun `a plan that does not fit the buffer is rejected`() {
        val tooBig = CaptureGeometry(
            srcX = 0, srcY = 0, srcWidth = 5000, srcHeight = 3024,
            rotationDegrees = 90, mirror = false, outWidth = 3024, outHeight = 5000,
        )
        assertNull(SubjectProjection.project(box(0.4f, 0.4f, 0.6f, 0.6f), tooBig, bufW, bufH))

        val negative = CaptureGeometry(
            srcX = -10, srcY = 0, srcWidth = 100, srcHeight = 100,
            rotationDegrees = 0, mirror = false, outWidth = 100, outHeight = 100,
        )
        assertNull(SubjectProjection.project(box(0.4f, 0.4f, 0.6f, 0.6f), negative, bufW, bufH))
    }

    @Test
    fun `non-finite and degenerate inputs are rejected rather than propagated`() {
        val plan = devicePlan(0.8f)
        assertNull(SubjectProjection.project(null, plan, bufW, bufH))
        assertNull(SubjectProjection.project(box(0.4f, 0.4f, 0.6f, 0.6f), null, bufW, bufH))
        assertNull(SubjectProjection.project(box(0.1f, 0.1f, 0.1f, 0.5f), plan, bufW, bufH))
        assertNull(SubjectProjection.project(box(0.5f, 0.1f, 0.2f, 0.5f), plan, bufW, bufH))
        assertNull(SubjectProjection.project(box(Float.NaN, 0.1f, 0.5f, 0.5f), plan, bufW, bufH))
        assertNull(SubjectProjection.project(box(0.1f, 0.1f, 0.5f, 0.5f), plan, 0, bufH))
        assertNull(SubjectProjection.project(box(0.1f, 0.1f, 0.5f, 0.5f), plan, bufW, 0))
        assertNull(
            SubjectProjection.project(
                box(0.1f, 0.1f, 0.5f, 0.5f), plan, bufW, bufH, sourceRatioWtoH = 0f,
            ),
        )
        assertNull(
            SubjectProjection.project(
                box(0.1f, 0.1f, 0.5f, 0.5f), plan, bufW, bufH, sourceRatioWtoH = Float.NaN,
            ),
        )
    }
}
