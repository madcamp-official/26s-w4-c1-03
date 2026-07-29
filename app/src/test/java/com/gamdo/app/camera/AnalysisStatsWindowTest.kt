package com.gamdo.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The analysis rate must be frames over the window that **actually elapsed**.
 *
 * `FrameAnalyzer` used to report `fps = processed` — a raw count over a window it
 * only checked was *at least* a second. The check fires on the first frame
 * arriving after the second is up, so at the measured 239ms per frame the window
 * really closes at ~1.19s and the count is inflated by roughly a fifth. The HUD
 * read `분석 239.3ms · 6fps` on SM-G970N, and those two numbers cannot both be
 * true: six frames of 239ms is 1.43 seconds of work inside one second.
 *
 * This is the same defect W3-2 was opened to fix one line above it —
 * [PreviewFrameMeter] states "frames over the window that actually elapsed, not
 * over a nominal" and computes it correctly — so leaving the analysis number
 * wrong while relabelling the preview one would have fixed the label and kept the
 * lie.
 *
 * The second half of the bug is *which* timestamp closes the window.
 * `FrameAnalyzer` captured `now` at the top of `analyze()`, before the detection
 * ran, and then used that same value as the next window's start. The last frame's
 * processing therefore fell outside both windows' elapsed time while its count
 * stayed inside — inflating the rate a second time. The window must close on a
 * timestamp taken *after* the work, which is what `nowNs` means here.
 */
class AnalysisStatsWindowTest {

    private val second = 1_000_000_000L

    @Test
    fun `nothing is emitted before a full window has elapsed`() {
        val w = AnalysisStatsWindow()
        assertNull(w.maybeEmit(0L))
        w.onProcessed(200.0)
        assertNull("999ms is not a window", w.maybeEmit(999_000_000L))
    }

    /**
     * The case from the device. Five frames at 239ms close the window at 1.195s,
     * so the honest rate is 5 / 1.195 = 4.2, not 5.
     */
    @Test
    fun `the rate divides by the elapsed window and not by a nominal second`() {
        val w = AnalysisStatsWindow()
        w.maybeEmit(0L)
        var t = 0L
        repeat(5) {
            t += 239_000_000L
            w.onProcessed(239.0)
        }

        val stats = w.maybeEmit(t)

        assertEquals("window was 1.195s, so 5 frames is 4fps and not 5", 4, stats!!.fps)
        assertEquals(239.0, stats.processMs, 0.01)
    }

    /**
     * A slow window must not be able to report a rate higher than the frame cost
     * allows. This is the invariant the HUD violated: `1000 / processMs` is the
     * ceiling, and the reported fps has to sit at or below it.
     */
    @Test
    fun `the reported rate never exceeds what the frame cost permits`() {
        for (frameMs in listOf(150L, 239L, 300L, 405L)) {
            val w = AnalysisStatsWindow()
            w.maybeEmit(0L)
            var t = 0L
            while (t < second) {
                t += frameMs * 1_000_000L
                w.onProcessed(frameMs.toDouble())
            }
            val stats = w.maybeEmit(t)!!
            val ceiling = 1000.0 / frameMs
            assert(stats.fps <= kotlin.math.ceil(ceiling).toInt()) {
                "at ${frameMs}ms per frame the ceiling is %.1f fps but the window reported ${stats.fps}"
                    .format(ceiling)
            }
        }
    }

    @Test
    fun `dropped frames count toward the drop rate but not the frame rate`() {
        val w = AnalysisStatsWindow()
        w.maybeEmit(0L)
        repeat(3) { w.onProcessed(100.0) }
        repeat(1) { w.onDropped() }

        val stats = w.maybeEmit(second)!!

        assertEquals(3, stats.fps)
        assertEquals("1 of 4 frames dropped", 25, stats.dropRatePercent)
    }

    @Test
    fun `a window with no processed frames reports zero cost rather than dividing by zero`() {
        val w = AnalysisStatsWindow()
        w.maybeEmit(0L)
        repeat(4) { w.onDropped() }

        val stats = w.maybeEmit(second)!!

        assertEquals(0.0, stats.processMs, 0.0)
        assertEquals(0, stats.fps)
        assertEquals(100, stats.dropRatePercent)
    }

    /** The next window starts where the last one closed — elapsed time is not lost. */
    @Test
    fun `consecutive windows do not overlap or leave a gap`() {
        val w = AnalysisStatsWindow()
        w.maybeEmit(0L)
        repeat(4) { w.onProcessed(250.0) }
        assertEquals(4, w.maybeEmit(second)!!.fps)

        // Second window: 2 frames over the next 2 seconds is 1fps, not 2.
        repeat(2) { w.onProcessed(500.0) }
        assertEquals(1, w.maybeEmit(3 * second)!!.fps)
    }
}
