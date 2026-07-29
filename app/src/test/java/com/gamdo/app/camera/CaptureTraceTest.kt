package com.gamdo.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shutter→saved breakdown, tested for the property that makes it worth having:
 * **each number is one stage, not a running total.**
 *
 * This exists because the owner has twice optimised a stage they had not measured
 * (cold-start blamed on CameraX init, an album grid misread twice). A breakdown
 * that silently reported cumulative times would produce exactly that failure again
 * — every stage after a slow one would look slow too, and the fix would land in
 * the wrong place.
 */
class CaptureTraceTest {

    private fun ms(value: Double): Long = (value * 1_000_000.0).toLong()

    @Test
    fun `each phase reports its own duration, not the elapsed total`() {
        val trace = CaptureTrace(startNs = ms(0.0))
        trace.mark(CapturePhase.CAMERA_X, ms(1082.0))
        trace.mark(CapturePhase.DECODE, ms(1296.0))
        trace.mark(CapturePhase.ENCODE, ms(1582.0))

        val line = trace.format()

        // 1296 - 1082 = 214, not 1296.
        assertTrue("decode must be its own 214ms, got: $line", line.contains("decode=214"))
        assertTrue("encode must be its own 286ms, got: $line", line.contains("encode=286"))
        assertTrue("cameraX runs from the shutter, got: $line", line.contains("cameraX=1082"))
    }

    @Test
    fun `the total is the shutter to the last mark`() {
        val trace = CaptureTrace(startNs = ms(0.0))
        trace.mark(CapturePhase.CAMERA_X, ms(1082.0))
        trace.mark(CapturePhase.ROW, ms(2247.0))

        // 2247ms is the owner's measured shutter→file-on-disk figure.
        assertTrue(trace.format(), trace.format().contains("total=2247ms"))
        assertEquals(2247.0, trace.totalMs()!!, 0.5)
    }

    @Test
    fun `a phase that never ran is absent, not zero`() {
        // The whole point of moving the gallery export off the shutter path is that
        // it no longer happens here. Printing `gallery=0` would read as "the export
        // was free", which is the opposite of true — it moved, it did not vanish.
        val trace = CaptureTrace(startNs = ms(0.0))
        trace.mark(CapturePhase.CAMERA_X, ms(1000.0))

        val line = trace.format()

        assertTrue(line, line.contains("cameraX="))
        assertTrue("an unrun phase must not be printed at all: $line", !line.contains("gallery"))
        assertTrue("an unrun phase must not be printed at all: $line", !line.contains("row="))
    }

    @Test
    fun `an empty trace says so instead of claiming a zero-cost capture`() {
        val trace = CaptureTrace(startNs = ms(0.0))

        assertNull(trace.totalMs())
        assertTrue(
            "an unmarked trace must not format as total=0ms: ${trace.format()}",
            !trace.format().contains("total=0ms"),
        )
    }

    @Test
    fun `marking the same phase twice keeps the first, so a boundary cannot be redefined`() {
        val trace = CaptureTrace(startNs = ms(0.0))
        trace.mark(CapturePhase.CAMERA_X, ms(1000.0))
        // A retry, a duplicated callback, a copy-pasted call site. Whatever the
        // cause, the second one would silently move the boundary between two
        // stages and make both numbers wrong.
        trace.mark(CapturePhase.CAMERA_X, ms(1500.0))
        trace.mark(CapturePhase.DECODE, ms(1200.0))

        val line = trace.format()

        assertTrue("first mark wins: $line", line.contains("cameraX=1000"))
        assertTrue("decode measured from the first cameraX mark: $line", line.contains("decode=200"))
    }

    @Test
    fun `marks out of order are printed as negative rather than clamped to zero`() {
        // A negative number in logcat is a loud "these marks are mis-ordered".
        // Clamping to 0 would print a plausible-looking free stage instead, which
        // is how a measurement bug survives a code review.
        val trace = CaptureTrace(startNs = ms(0.0))
        trace.mark(CapturePhase.CAMERA_X, ms(1000.0))
        trace.mark(CapturePhase.DECODE, ms(900.0))

        assertTrue(trace.format(), trace.format().contains("decode=-100"))
    }

    @Test
    fun `phases print in the order the capture path runs them`() {
        val trace = CaptureTrace(startNs = 0L)
        trace.mark(CapturePhase.CAMERA_X, ms(10.0))
        trace.mark(CapturePhase.DECODE, ms(20.0))
        trace.mark(CapturePhase.CROP, ms(30.0))
        trace.mark(CapturePhase.ENCODE, ms(40.0))
        trace.mark(CapturePhase.APP_FILE, ms(50.0))
        trace.mark(CapturePhase.ROW, ms(60.0))

        assertEquals(
            "cameraX=10 decode=10 crop=10 encode=10 appFile=10 row=10 total=60ms",
            trace.format(),
        )
    }
}
