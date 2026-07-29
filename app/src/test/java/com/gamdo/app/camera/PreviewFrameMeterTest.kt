package com.gamdo.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * W3-2. The preview rate is a different quantity from the analysis rate and the
 * HUD used to print one under the other's name — ~3fps where the preview was
 * visibly smooth, which reads as a failed §7-1 target that was never measured.
 *
 * This class is the whole measurement. The Android side is one call
 * (`PreviewView.setFrameUpdateListener`) that hands over one callback per preview
 * frame; everything that turns those into a number is here, with no `android.*`
 * import, so it is decided by tests rather than by a device run.
 */
class PreviewFrameMeterTest {

    /** Timestamp of preview frame [i] at a steady [fps], exact in nanoseconds. */
    private fun at(i: Int, fps: Int = 30): Long = i * 1_000_000_000L / fps

    @Test
    fun `nothing is reported before a full window has elapsed`() {
        val meter = PreviewFrameMeter()

        // 30fps for half a second. Emitting here would report a rate measured over
        // an arbitrary fraction of a second.
        repeat(15) { i -> assertNull(meter.onFrame(at(i))) }
    }

    @Test
    fun `a steady 30fps preview reports 30fps`() {
        val meter = PreviewFrameMeter()
        var reported: PreviewStats? = null

        // Frame 0 opens the window; frames 1..30 land in it, the last at exactly 1s.
        for (i in 0..30) meter.onFrame(at(i))?.let { reported = it }

        val stats = requireNotNull(reported) { "the window never closed" }
        assertEquals(30, stats.fps)
        assertEquals(30, stats.frames)
        assertEquals(1000.0, stats.windowMs, 0.001)
    }

    @Test
    fun `a steady 60fps preview reports 60fps`() {
        val meter = PreviewFrameMeter()
        var reported: PreviewStats? = null
        for (i in 0..60) meter.onFrame(at(i, fps = 60))?.let { reported = it }

        assertEquals(60, requireNotNull(reported).fps)
    }

    /**
     * The rate is computed over the window that actually elapsed, not over the
     * nominal one second. A preview that stalls mid-window must report the lower
     * rate it achieved rather than the rate it was asked for.
     */
    @Test
    fun `a stalled preview reports the rate it actually achieved`() {
        val meter = PreviewFrameMeter()
        var reported: PreviewStats? = null

        // Ten frames at 30fps (0..300ms), then a 1.2s stall. The frame that closes
        // the window lands at 1500ms, well past the nominal second — which is the
        // point: 10 frames over 1.5s is 6.7fps, and only a divisor taken from the
        // clock rather than from the nominal window can say so.
        for (i in 0..9) meter.onFrame(at(i))?.let { reported = it }
        meter.onFrame(1_500_000_000L)?.let { reported = it }

        val stats = requireNotNull(reported)
        assertEquals(7, stats.fps)
        assertEquals(10, stats.frames)
        assertEquals(1500.0, stats.windowMs, 0.001)
    }

    @Test
    fun `each window starts from the frame that closed the previous one`() {
        val meter = PreviewFrameMeter()
        val reports = mutableListOf<PreviewStats>()

        for (i in 0..90) meter.onFrame(at(i))?.let { reports += it }

        assertEquals("one report per second", 3, reports.size)
        // No frame is counted twice and none is lost between windows, so the second
        // and third seconds must read exactly like the first.
        assertEquals(listOf(30, 30, 30), reports.map { it.fps })
        assertEquals(listOf(30, 30, 30), reports.map { it.frames })
    }

    @Test
    fun `reset drops the open window so a rebind does not span the gap`() {
        val meter = PreviewFrameMeter()
        for (i in 0..20) meter.onFrame(at(i))

        // The camera goes to background here and comes back 40 seconds later. Left
        // alone, the next frame would close a 40-second window and report 0fps.
        meter.reset()

        val resumeNs = 40_000_000_000L
        assertNull("the first frame after a reset only opens a window", meter.onFrame(resumeNs))
        var reported: PreviewStats? = null
        for (i in 1..30) meter.onFrame(resumeNs + at(i))?.let { reported = it }

        assertEquals(30, requireNotNull(reported).fps)
    }

    @Test
    fun `a non-positive window is rejected`() {
        assertEquals(
            IllegalArgumentException::class.java,
            runCatching { PreviewFrameMeter(windowNs = 0L) }.exceptionOrNull()?.javaClass,
        )
    }
}
