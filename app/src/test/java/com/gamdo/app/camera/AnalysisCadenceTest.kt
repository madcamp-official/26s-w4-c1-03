package com.gamdo.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The analysis throttle, tested against numbers measured on SM-G970N rather than
 * against numbers chosen to make it pass.
 *
 * The throttle is the only lever `FrameAnalyzer` has over how often the detection
 * stack runs, and the whole reason W3-1 externalises it. What these tests
 * establish is **when it is a lever and when it is decoration** — a gate that
 * cannot fire is worse than no gate, because it looks like a working safeguard in
 * code review.
 */
class AnalysisCadenceTest {

    /** ns per ms, for reading the measured figures below at their natural scale. */
    private fun ms(value: Double): Long = (value * 1_000_000.0).toLong()

    @Test
    fun `a target of zero fps is rejected rather than dividing by zero`() {
        // 1_000_000_000L / 0 is an ArithmeticException at construction, i.e. a
        // crash on the analysis thread the first time a bad config ships.
        val failure = runCatching { AnalysisCadence(targetFps = 0) }.exceptionOrNull()
        assertTrue(
            "expected IllegalArgumentException, got $failure",
            failure is IllegalArgumentException,
        )
        assertTrue(
            runCatching { AnalysisCadence(targetFps = -1) }.exceptionOrNull()
                is IllegalArgumentException,
        )
    }

    @Test
    fun `the shipped 12fps target spaces frames 83ms apart`() {
        assertEquals(83_333_333L, AnalysisCadence(targetFps = 12).minIntervalNs)
        assertEquals(125_000_000L, AnalysisCadence(targetFps = 8).minIntervalNs)
    }

    @Test
    fun `the first frame is never dropped`() {
        // Guards a real edge: the pre-W3-1 code compared against a `lastProcessedNs`
        // of 0, so on any clock that starts near zero the first frame was dropped.
        // System.nanoTime() is large enough on device to hide it, which is exactly
        // the kind of accident a synthetic clock exists to catch.
        val cadence = AnalysisCadence(targetFps = 12)
        assertTrue(cadence.shouldProcess(nowNs = 0L))
    }

    @Test
    fun `the gate opens one interval after the last processed frame, not before`() {
        val cadence = AnalysisCadence(targetFps = 12)
        assertTrue(cadence.shouldProcess(0L))

        assertFalse("83.3ms is the boundary and must still be closed", cadence.shouldProcess(ms(83.0)))
        assertTrue("84ms is past the interval", cadence.shouldProcess(ms(84.0)))
        // The clock restarts from the frame that was *processed*, not from the one
        // that was dropped: 84 + 84 = 168ms, so 150ms is still inside the interval.
        assertFalse(cadence.shouldProcess(ms(150.0)))
        assertTrue(cadence.shouldProcess(ms(168.0)))
    }

    @Test
    fun `at preview rate the gate really does drop frames`() {
        // 33.3ms apart = 30fps. This is the case the throttle was written for, and
        // it works: exactly two of every three frames are refused.
        val cadence = AnalysisCadence(targetFps = 12)
        var processed = 0
        repeat(90) { i -> if (cadence.shouldProcess(ms(i * 33.333))) processed++ }

        assertEquals("90 frames over 3s at a 12fps gate", 30, processed)
    }

    /**
     * **The measurement that decides W3-1's second half.**
     *
     * Measured on SM-G970N (2026-07-29): one analysed frame costs 200-400ms end to
     * end, median 208.8ms warm and 178.5ms cool. Frames cannot arrive at the
     * analyzer faster than they are processed — `STRATEGY_KEEP_ONLY_LATEST` hands
     * the next one over only after `analyze()` returns — so the interval the gate
     * sees is the *processing* cost, never the preview's 33ms.
     *
     * 178.5ms is more than double the 83.3ms interval, so the gate is open every
     * single time it is consulted. `dropRatePercent` is therefore structurally 0 on
     * the analysis path, and the throttle sets nothing: throughput is the model
     * cost alone.
     */
    @Test
    fun `at the measured frame cost the 12fps gate never fires`() {
        val cadence = AnalysisCadence(targetFps = 12)
        var dropped = 0
        // The coolest, i.e. fastest, interval measured. Anything hotter is slower
        // still and drops even less.
        repeat(200) { i -> if (!cadence.shouldProcess(ms(i * 178.5))) dropped++ }

        assertEquals("the throttle cannot fire below 12fps of real throughput", 0, dropped)
    }

    /**
     * **Why the planned 12→8 fps thermal downgrade would change nothing.**
     *
     * Lowering the target raises the minimum interval to 125ms, which is still
     * under the 178.5ms the pipeline already takes at its fastest. Both targets
     * drop zero frames on the same input, so the "downgrade" would be observable
     * only as a different number in the config file.
     */
    @Test
    fun `dropping the target to 8fps drops no more frames than 12fps at the measured cost`() {
        val measuredIntervals = listOf(178.5, 208.8, 264.0, 302.6, 405.4)

        for (interval in measuredIntervals) {
            val twelve = AnalysisCadence(targetFps = 12)
            val eight = AnalysisCadence(targetFps = 8)
            var twelveProcessed = 0
            var eightProcessed = 0
            repeat(100) { i ->
                if (twelve.shouldProcess(ms(i * interval))) twelveProcessed++
                if (eight.shouldProcess(ms(i * interval))) eightProcessed++
            }

            assertEquals("12fps target at ${interval}ms/frame", 100, twelveProcessed)
            assertEquals("8fps target at ${interval}ms/frame", 100, eightProcessed)
        }
    }

    @Test
    fun `reset makes the next frame unconditional`() {
        val cadence = AnalysisCadence(targetFps = 12)
        assertTrue(cadence.shouldProcess(0L))
        assertFalse(cadence.shouldProcess(ms(10.0)))

        cadence.reset()

        assertTrue("a rebind must not inherit the old frame's clock", cadence.shouldProcess(ms(10.0)))
    }
}
