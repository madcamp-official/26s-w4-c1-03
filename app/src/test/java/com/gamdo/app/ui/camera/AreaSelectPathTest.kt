package com.gamdo.app.ui.camera

import com.gamdo.app.guide.PreviewGeometry
import com.gamdo.app.guide.ScenePolygonRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lasso path collection (연필 = 영역 선택). P1 owns collection; P2 owns validity. */
class AreaSelectPathTest {

    private val step = 6f

    // ---- thinning ---------------------------------------------------------------

    @Test
    fun `the first sample is always kept`() {
        assertEquals(listOf(10f to 20f), AreaSelectPath.appended(emptyList(), 10f, 20f, step))
    }

    @Test
    fun `a sample closer than the step is dropped`() {
        val path = listOf(100f to 100f)
        assertEquals(path, AreaSelectPath.appended(path, 102f, 101f, step))
    }

    @Test
    fun `a sample past the step is kept`() {
        val path = listOf(100f to 100f)
        assertEquals(
            path + (104f to 103f),
            AreaSelectPath.appended(path, 104f, 103f, step),
        )
    }

    @Test
    fun `the step is Manhattan, matching P2's simplify`() {
        val path = listOf(0f to 0f)
        // Euclidean distance 5.0 — under the step — but Manhattan 7.0, which is over.
        // P2's `simplify` uses the same metric, so the two thinning passes agree.
        assertEquals(2, AreaSelectPath.appended(path, 3f, 4f, step).size)
    }

    @Test
    fun `a drag across the screen is bounded, not unbounded`() {
        var path = emptyList<Pair<Float, Float>>()
        // One sample per frame down a 2000px screen, as a slow 4-second drag would.
        for (i in 0 until 480) path = AreaSelectPath.appended(path, 500f, i * 4f, step)
        assertTrue("thinning must bound the drawn path; got ${path.size}", path.size < 400)
        assertTrue(path.size > 100)
    }

    @Test
    fun `a non-finite sample is dropped rather than poisoning the polygon`() {
        val path = listOf(10f to 10f)
        assertEquals(path, AreaSelectPath.appended(path, Float.NaN, 50f, step))
        assertEquals(path, AreaSelectPath.appended(path, 50f, Float.POSITIVE_INFINITY, step))
    }

    // ---- closing ---------------------------------------------------------------

    /**
     * "손을 떼면 경로를 자동으로 닫는다" needs no code — `polygonArea` walks
     * `points[(i + 1) % size]`, so the closing edge exists as soon as the list is
     * read as a polygon. This pins that, because the obvious implementation
     * (append the first point again) adds a zero-length edge nothing would report.
     */
    @Test
    fun `an open list is already a closed ring downstream`() {
        val square = listOf(
            0.2f to 0.2f, 0.8f to 0.2f, 0.8f to 0.8f, 0.2f to 0.8f,
        ).map { com.gamdo.app.guide.PointN(it.first, it.second) }
        val open = ScenePolygonRegion.fromNormalized(square)
        val explicitlyClosed = ScenePolygonRegion.fromNormalized(square + square.first())
        assertNotNull(open)
        assertNotNull(explicitlyClosed)
        assertEquals(
            "re-appending the first point must not change the area — it only adds a " +
                "zero-length edge",
            open!!.areaRatio,
            explicitlyClosed!!.areaRatio,
            1e-6f,
        )
        assertTrue("a 0.6 x 0.6 square is 36% of the frame", open.areaRatio in 0.35f..0.37f)
    }

    // ---- what is worth submitting ----------------------------------------------

    @Test
    fun `fewer than three vertices is a tap, not a path`() {
        assertFalse(AreaSelectPath.isWorthSubmitting(emptyList()))
        assertFalse(AreaSelectPath.isWorthSubmitting(listOf(1f to 1f)))
        assertFalse(AreaSelectPath.isWorthSubmitting(listOf(1f to 1f, 9f to 9f)))
        assertTrue(AreaSelectPath.isWorthSubmitting(listOf(1f to 1f, 9f to 1f, 9f to 9f)))
    }

    /**
     * A path that fails on **area** must still be submitted: P2 owns the threshold,
     * and `rescanLayoutInPolygon` returning false is what tells the screen to show
     * the rejection. P1 duplicating the 2%..80% band is how the two copies drift.
     */
    @Test
    fun `an area rejection is P2's answer, not something P1 pre-empts`() {
        val tiny = listOf(0f to 0f, 4f to 0f, 4f to 4f)
        assertTrue(
            "P1 must hand this over and let P2 reject it",
            AreaSelectPath.isWorthSubmitting(tiny),
        )
        val geometry = PreviewGeometry(1000, 1000, 1000, 1000)
        assertNull(
            "P2 rejects it on area — which is the signal P1 renders",
            ScenePolygonRegion.fromViewPath(tiny, geometry),
        )
    }

    // ---- clamping to the visible window ----------------------------------------

    @Test
    fun `a sample inside the window passes through`() {
        // 1080x2000 pane at 4:5 -> window 1350 tall, bars 325 each.
        val clamped = AreaSelectPath.clampToWindow(500f, 1000f, 1080f, 2000f, 0.8f)
        assertEquals(500f to 1000f, clamped)
    }

    /**
     * `resolveTapFocusPoint` *rejects* a letterbox tap, which is right for a focus
     * tap. A lasso is a stroke: dropping its middle splices the path straight across
     * the subject, so it rides the boundary instead.
     */
    @Test
    fun `a sample in the letterbox rides the boundary instead of vanishing`() {
        val top = AreaSelectPath.clampToWindow(500f, 10f, 1080f, 2000f, 0.8f)
        assertEquals(500f to 325f, top)
        val bottom = AreaSelectPath.clampToWindow(500f, 1990f, 1080f, 2000f, 0.8f)
        assertEquals(500f to 1675f, bottom)
    }

    @Test
    fun `a sample off the side is clamped too`() {
        assertEquals(0f to 1000f, AreaSelectPath.clampToWindow(-40f, 1000f, 1080f, 2000f, 0.8f))
        assertEquals(1080f to 1000f, AreaSelectPath.clampToWindow(9999f, 1000f, 1080f, 2000f, 0.8f))
    }

    @Test
    fun `a pane too short for the ratio has no bars to clamp to`() {
        // 1080x1000 at 4:5 wants 1350 tall; coerced to 1000, so barHeight is 0.
        assertEquals(500f to 0f, AreaSelectPath.clampToWindow(500f, -5f, 1080f, 1000f, 0.8f))
        assertEquals(500f to 1000f, AreaSelectPath.clampToWindow(500f, 5000f, 1080f, 1000f, 0.8f))
    }

    @Test
    fun `unmeasured panes and non-finite samples get no answer`() {
        assertNull(AreaSelectPath.clampToWindow(Float.NaN, 10f, 1080f, 2000f, 0.8f))
        assertNull(AreaSelectPath.clampToWindow(10f, 10f, 0f, 2000f, 0.8f))
        assertNull(AreaSelectPath.clampToWindow(10f, 10f, 1080f, 0f, 0.8f))
        assertNull(AreaSelectPath.clampToWindow(10f, 10f, 1080f, 2000f, 0f))
        assertNull(AreaSelectPath.clampToWindow(10f, 10f, 1080f, Float.NaN, 0.8f))
    }

    /** The clamp and the mask must agree, or the path draws outside what it can select. */
    @Test
    fun `the clamp's window matches the mask's and the focus rule's`() {
        val paneW = 1080f
        val paneH = 2000f
        for (ratio in listOf(0.8f, 1f)) {
            val windowHeight = (paneW / ratio).coerceAtMost(paneH)
            val barHeight = (paneH - windowHeight) / 2f
            val (_, y) = AreaSelectPath.clampToWindow(10f, 0f, paneW, paneH, ratio)!!
            assertEquals("top of window at ratio $ratio", barHeight, y, 0f)
            assertNull(
                "the focus rule rejects exactly what the clamp pulls in",
                resolveTapFocusPoint(10f, barHeight - 1f, paneW, paneH, ratio),
            )
            assertNotNull(resolveTapFocusPoint(10f, barHeight, paneW, paneH, ratio))
        }
    }

    // ---- leaving the mode -------------------------------------------------------

    @Test
    fun `disarming without drawing leaves the existing fix alone`() {
        assertEquals(
            "cancelPolygonLayoutSearch resets the alignment engine and the stabilizer; " +
                "a user who drew nothing must not lose the layout they had",
            AreaSelectExit.LEAVE_GUIDE_ALONE,
            AreaSelectExit.forExit(scopeIsPolygon = false),
        )
    }

    @Test
    fun `disarming a live polygon search hands the guide back to automatic search`() {
        assertEquals(
            AreaSelectExit.CANCEL_POLYGON_SEARCH,
            AreaSelectExit.forExit(scopeIsPolygon = true),
        )
    }

    // ---- handing the region to P2 -----------------------------------------------

    private class RecordingSubmit(private val answer: Boolean = true) :
        (List<Pair<Float, Float>>, PreviewGeometry) -> Boolean {
        var calls = 0
        var lastGeometry: PreviewGeometry? = null
        override fun invoke(points: List<Pair<Float, Float>>, geometry: PreviewGeometry): Boolean {
            calls++
            lastGeometry = geometry
            return answer
        }
    }

    private val triangle = listOf(200f to 400f, 800f to 400f, 800f to 1200f)

    @Test
    fun `a usable region is handed over with the pane's geometry`() {
        val submit = RecordingSubmit()
        assertTrue(
            submitLassoRegion(triangle, 1080f, 2000f, 480, 640, mirror = false, submit = submit),
        )
        assertEquals(1, submit.calls)
        assertEquals(1080, submit.lastGeometry!!.viewWidth)
        assertEquals(2000, submit.lastGeometry!!.viewHeight)
        assertEquals(480, submit.lastGeometry!!.analysisWidth)
        assertEquals(640, submit.lastGeometry!!.analysisHeight)
    }

    @Test
    fun `the front lens mirror reaches the geometry`() {
        val submit = RecordingSubmit()
        submitLassoRegion(triangle, 1080f, 2000f, 480, 640, mirror = true, submit = submit)
        assertTrue(submit.lastGeometry!!.mirror)
    }

    @Test
    fun `a tap that happened while armed calls nothing`() {
        val submit = RecordingSubmit()
        assertFalse(
            submitLassoRegion(
                listOf(500f to 500f), 1080f, 2000f, 480, 640, mirror = false, submit = submit,
            ),
        )
        assertEquals("§4 P2-1: 서버·탐지를 호출하지 않는다", 0, submit.calls)
    }

    /**
     * `PreviewGeometry`'s `init` *requires* positive dimensions, so the zeroes standing
     * for "no analysis frame yet" would throw `IllegalArgumentException` out of a gesture
     * callback and take the screen down with it. This is the crash, pinned.
     */
    @Test
    fun `no analysis frame yet is declined, not crashed`() {
        val submit = RecordingSubmit()
        assertFalse(
            submitLassoRegion(triangle, 1080f, 2000f, 0, 0, mirror = false, submit = submit),
        )
        assertEquals(0, submit.calls)
    }

    @Test
    fun `an unmeasured pane is declined too`() {
        val submit = RecordingSubmit()
        assertFalse(submitLassoRegion(triangle, 0f, 2000f, 480, 640, false, submit))
        assertFalse(submitLassoRegion(triangle, 1080f, 0f, 480, 640, false, submit))
        assertEquals(0, submit.calls)
    }

    /** P2's rejection passes straight through — P1 holds no copy of the area band. */
    @Test
    fun `P2's answer is the answer`() {
        val rejecting = RecordingSubmit(answer = false)
        assertFalse(
            submitLassoRegion(triangle, 1080f, 2000f, 480, 640, mirror = false, submit = rejecting),
        )
        assertEquals("it must still have been asked", 1, rejecting.calls)
    }
}
