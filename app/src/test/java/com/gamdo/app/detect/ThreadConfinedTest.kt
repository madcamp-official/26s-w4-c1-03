package com.gamdo.app.detect

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The thread-affinity hedge for the GPU object detector, tested where it *can* be
 * tested — the marshalling, not the delegate.
 *
 * ## Why the GPU detector is confined at all
 *
 * The upgraded detector is built on a background thread (a 7.5s TFLite GPU delegate
 * compilation must not sit on the analysis executor) and then used from the CameraX
 * analysis thread. Whether MediaPipe Tasks tolerates that split was the open
 * question, and the evidence found in `tasks-core-0.10.26` is suggestive rather than
 * normative: `TaskRunner.process` is `synchronized` and its bytecode is
 * `addPackets(...)` → `Graph.waitUntilGraphIdle()` → read the cached result, so the
 * calling thread pushes packets and blocks while the MediaPipe graph runs the
 * calculators — and the TFLite GPU delegate's GL context — on scheduler threads of
 * its own. That makes cross-thread use *probably* fine. Probably is not a basis for
 * a native crash on hardware we cannot reproduce, so the detector is confined
 * instead: created, validated and invoked on one thread for its whole life.
 *
 * Confinement also buys something the cross-thread version cannot have at any
 * confidence level. On SM-G970N the GPU delegate is *created* successfully and the
 * *first inference* fails, so the upgrade is only adopted after a validation
 * inference — and a validation inference is only evidence about the thread that
 * will run the production ones. Same thread, same proof.
 *
 * The cost is one thread hop per analysed frame against a GPU inference measured in
 * tens of milliseconds.
 */
class ThreadConfinedTest {

    @Test
    fun `an unconfined value is used on the calling thread`() {
        val confined = ThreadConfined.unconfined("cpu-detector")
        val caller = Thread.currentThread()

        val ranOn = confined.use { Thread.currentThread() }

        assertSame("the CPU detector is built and used on the analysis thread", caller, ranOn)
    }

    /**
     * The property the whole design rests on: whoever calls, the value is touched on
     * its owner thread and nowhere else.
     */
    @Test
    fun `a confined value is used on its owner thread and never on the caller's`() {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "owner") }
        try {
            val built = executor.submit<Pair<ThreadConfined<String>, Thread>> {
                ThreadConfined.confinedTo(executor, "gpu-detector") to Thread.currentThread()
            }.get()
            val (confined, ownerThread) = built

            val ranOn = confined.use { Thread.currentThread() }

            assertSame("every call must land on the thread that built the value", ownerThread, ranOn)
            assertNotEquals(Thread.currentThread(), ranOn)
            assertEquals("owner", ranOn.name)
        } finally {
            executor.shutdownNow()
        }
    }

    /**
     * The validation inference runs on the owner thread *before* the value is
     * published, i.e. from inside the owner thread itself. If that re-entered the
     * executor and waited, a single-thread executor would deadlock and the upgrade
     * would hang forever instead of failing.
     */
    @Test
    fun `a call from the owner thread runs inline instead of deadlocking`() {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "owner") }
        try {
            val result = executor.submit<String> {
                val confined = ThreadConfined.confinedTo(executor, "gpu-detector")
                // This is the validation inference: same thread, no re-entry.
                confined.use { value -> "$value on ${Thread.currentThread().name}" }
            }.get(5, TimeUnit.SECONDS)

            assertEquals("gpu-detector on owner", result)
        } finally {
            executor.shutdownNow()
        }
    }

    /**
     * A GL fault has to reach the caller as itself. The detector's mid-session
     * downgrade branches on the throwable and records its class name, and
     * `ExecutionException: java.lang.IllegalStateException: …` in the accelerator
     * record would name the marshalling, not the fault.
     */
    @Test
    fun `a failure inside the block reaches the caller with its own type`() {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "owner") }
        try {
            val confined = executor.submit<ThreadConfined<String>> {
                ThreadConfined.confinedTo(executor, "gpu-detector")
            }.get()

            val thrown = assertThrows(IllegalStateException::class.java) {
                confined.use { error("[GL_INVALID_VALUE]: glMapBufferRange") }
            }

            assertEquals("[GL_INVALID_VALUE]: glMapBufferRange", thrown.message)
        } finally {
            executor.shutdownNow()
        }
    }

    /** Native handles are closed on the thread that owns them, then the thread ends. */
    @Test
    fun `close releases on the owner thread and shuts the thread down`() {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "owner") }
        val confined = executor.submit<ThreadConfined<String>> {
            ThreadConfined.confinedTo(executor, "gpu-detector")
        }.get()
        var closedOn: Thread? = null

        confined.close { closedOn = Thread.currentThread() }

        assertEquals("owner", closedOn?.name)
        assertTrue("the confinement thread must not outlive the value", executor.isShutdown)
    }

    /**
     * `close` races with itself: the analysis thread closes on teardown while the
     * upgrade thread closes a detector it has just decided not to publish. Closing a
     * MediaPipe handle twice is a native double-free.
     */
    @Test
    fun `close runs the release exactly once`() {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "owner") }
        val confined = executor.submit<ThreadConfined<String>> {
            ThreadConfined.confinedTo(executor, "gpu-detector")
        }.get()
        var releases = 0

        confined.close { releases++ }
        confined.close { releases++ }

        assertEquals(1, releases)
    }

    /**
     * After close the value is gone. Reporting that honestly is what lets the
     * detector's per-frame path fall back to CPU instead of calling into a freed
     * native handle.
     */
    @Test
    fun `using a closed value fails instead of touching it`() {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "owner") }
        val confined = executor.submit<ThreadConfined<String>> {
            ThreadConfined.confinedTo(executor, "gpu-detector")
        }.get()
        confined.close { }

        var touched = false
        assertThrows(IllegalStateException::class.java) {
            confined.use { touched = true }
        }

        assertFalse("a closed detector must never be handed to a caller", touched)
    }

    /** Diagnostics: the device log has to be able to name the thread the GPU runs on. */
    @Test
    fun `a confined value can name its owner thread for the log`() {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "gamdo-gpu-upgrade") }
        try {
            val confined = executor.submit<ThreadConfined<String>> {
                ThreadConfined.confinedTo(executor, "gpu-detector")
            }.get()

            assertEquals("gamdo-gpu-upgrade", confined.ownerName)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `an unconfined value has no owner thread to name`() {
        assertEquals(null, ThreadConfined.unconfined("cpu-detector").ownerName)
    }
}
