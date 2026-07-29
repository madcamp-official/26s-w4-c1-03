package com.gamdo.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * "Is the detection stack allowed to run right now?" — the whole of it, on the JVM.
 *
 * The failure this class exists to make impossible is **not** a slow shutter. It
 * is a pause that never lifts: a camera whose guide is dead for the rest of the
 * session, with no crash, no log and nothing on screen to say so. Every test below
 * is about that one asymmetry — resuming early is harmless, staying paused is not
 * — so the gate is built to fail towards RUNNING in every direction.
 */
class AnalysisPauseGateTest {

    private fun ms(value: Long): Long = value * 1_000_000L

    @Test
    fun `a fresh gate lets analysis run`() {
        assertFalse(AnalysisPauseGate().isPaused(nowNs = 0L))
    }

    @Test
    fun `a capture in flight pauses analysis`() {
        val gate = AnalysisPauseGate()
        gate.pause(nowNs = ms(0))

        assertTrue(gate.isPaused(ms(100)))
    }

    @Test
    fun `the token from that pause resumes it`() {
        val gate = AnalysisPauseGate()
        val token = gate.pause(ms(0))
        assertTrue(gate.isPaused(ms(100)))

        gate.resume(token)

        assertFalse(gate.isPaused(ms(101)))
    }

    @Test
    fun `resuming twice is not an error and does not re-pause`() {
        // The shutter's `finally` and the screen's `onDispose` can both fire for the
        // same capture when the user navigates away mid-shot.
        val gate = AnalysisPauseGate()
        val token = gate.pause(ms(0))

        gate.resume(token)
        gate.resume(token)

        assertFalse(gate.isPaused(ms(50)))
    }

    @Test
    fun `a stale token cannot resume a newer capture's pause`() {
        val gate = AnalysisPauseGate()
        val first = gate.pause(ms(0))
        val second = gate.pause(ms(10))
        assertNotEquals(first, second)

        // The first capture finally completes and hands its token back. The second
        // is still exposing, so analysis must stay paused.
        gate.resume(first)
        assertTrue("a late resume must not release the capture that is still running", gate.isPaused(ms(20)))

        gate.resume(second)
        assertFalse(gate.isPaused(ms(30)))
    }

    @Test
    fun `resumeAll releases whatever is held, whoever holds it`() {
        // Screen teardown. There is no token to hand back here — the coroutine that
        // owned it was cancelled — so the release has to be unconditional.
        val gate = AnalysisPauseGate()
        gate.pause(ms(0))
        gate.pause(ms(5))

        gate.resumeAll()

        assertFalse(gate.isPaused(ms(10)))
    }

    /**
     * **The defect this whole class is for.**
     *
     * Every resume path can be reasoned about and every one of them can still be
     * missed: a `finally` that never ran because the coroutine was never started, a
     * process moved to the background between the pause and the resume, a future
     * edit that adds an early `return`. So the last guarantee does not depend on
     * anyone calling anything: the analysis thread asks the gate whether the pause
     * is still *valid*, and a pause older than the deadline is not.
     */
    @Test
    fun `a pause whose resume never arrives expires on its own`() {
        val gate = AnalysisPauseGate(maxPauseMs = 4_000L)
        gate.pause(ms(0))

        assertTrue("still inside a plausible capture", gate.isPaused(ms(3_999)))
        assertFalse("no capture takes four seconds; the guide comes back", gate.isPaused(ms(4_000)))
        assertFalse("and stays back", gate.isPaused(ms(4_001)))
    }

    @Test
    fun `an expired pause is counted so it can be logged as the defect it is`() {
        val gate = AnalysisPauseGate(maxPauseMs = 1_000L)
        assertEquals(0, gate.watchdogTrips)

        gate.pause(ms(0))
        gate.isPaused(ms(500))
        assertEquals("a live pause is not a trip", 0, gate.watchdogTrips)

        gate.isPaused(ms(1_000))
        assertEquals(1, gate.watchdogTrips)

        // Counted once per stuck pause, not once per frame that finds it stuck.
        gate.isPaused(ms(1_100))
        gate.isPaused(ms(1_200))
        assertEquals(1, gate.watchdogTrips)
    }

    @Test
    fun `an expired pause does not block the next capture from pausing again`() {
        val gate = AnalysisPauseGate(maxPauseMs = 1_000L)
        gate.pause(ms(0))
        assertFalse(gate.isPaused(ms(1_000)))

        val token = gate.pause(ms(2_000))
        assertTrue(gate.isPaused(ms(2_100)))
        gate.resume(token)
        assertFalse(gate.isPaused(ms(2_200)))
    }

    @Test
    fun `a disabled gate never pauses, so the change can be measured with and without`() {
        val gate = AnalysisPauseGate(isEnabled = false)
        val token = gate.pause(ms(0))

        assertFalse(gate.isEnabled)
        assertFalse(gate.isPaused(ms(1)))
        gate.resume(token)
        assertFalse(gate.isPaused(ms(2)))
    }

    @Test
    fun `a zero or negative deadline is rejected instead of pausing forever`() {
        // maxPauseMs <= 0 would make `now - since < maxPauseNs` false immediately —
        // harmless — but a config typo of the other sign is the dangerous one, and
        // rejecting at construction is where a bad value should die either way.
        assertTrue(
            runCatching { AnalysisPauseGate(maxPauseMs = 0L) }.exceptionOrNull()
                is IllegalArgumentException,
        )
        assertTrue(
            runCatching { AnalysisPauseGate(maxPauseMs = -1L) }.exceptionOrNull()
                is IllegalArgumentException,
        )
    }

    @Test
    fun `after any balanced sequence of captures the gate is running`() {
        val gate = AnalysisPauseGate()
        var now = 0L
        repeat(500) {
            val token = gate.pause(ms(now))
            now += 20
            gate.resume(token)
            now += 5
        }

        assertFalse(gate.isPaused(ms(now)))
    }

    @Test
    fun `concurrent shutter and analysis threads never leave the gate stuck`() {
        // The gate is read from the analysis thread and written from the main
        // thread, so "it works when one thread does it" is not the claim that
        // matters. 200 captures, each resumed, with a reader spinning throughout.
        val gate = AnalysisPauseGate(maxPauseMs = 60_000L)
        val pool = Executors.newFixedThreadPool(2)
        val done = CountDownLatch(1)
        try {
            pool.submit {
                repeat(200) {
                    val token = gate.pause(System.nanoTime())
                    gate.resume(token)
                }
                done.countDown()
            }
            pool.submit {
                while (done.count > 0L) gate.isPaused(System.nanoTime())
            }
            assertTrue("shutter loop did not finish", done.await(10, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }

        assertFalse("the gate must not be left paused", gate.isPaused(System.nanoTime()))
        assertEquals("no pause should have reached the deadline", 0, gate.watchdogTrips)
    }
}
