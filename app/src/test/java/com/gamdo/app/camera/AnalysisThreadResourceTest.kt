package com.gamdo.app.camera

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the fix for "the camera preview is black for 6.4s after launch".
 *
 * The bug was an ordering fact, not a slow model: the detection stack was built
 * inside a `remember { }` in `CameraScreen`, so composition — the main thread —
 * sat inside `ObjectDetector.createFromOptions` and the `AndroidView` factory
 * that calls `bindToLifecycle` further down the same composable could not run
 * until it returned. CameraX had the camera open at +1.1s and was told to attach
 * use cases at +7.5s.
 *
 * So the two properties worth a test are exactly those:
 *
 *  1. constructing the holder **must not block the constructing thread**, and
 *  2. work queued on the same executor afterwards **must still see the built
 *     resource** — otherwise the first analysed frames would race the build and
 *     we would have traded a black preview for a null detector.
 *
 * Both are decided by the executor's FIFO contract, which is why a fake executor
 * would prove nothing here: these tests use a real single-threaded executor and
 * gate the factory on a latch, so "the constructor returned while the build was
 * still running" is observed rather than assumed.
 */
class AnalysisThreadResourceTest {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    @After
    fun tearDown() {
        executor.shutdownNow()
    }

    /** Drains everything already queued, so assertions run against a settled state. */
    private fun drain() {
        executor.shutdown()
        assertTrue("executor did not drain", executor.awaitTermination(5, TimeUnit.SECONDS))
    }

    @Test
    fun `constructing the holder does not wait for the build`() {
        val proceed = CountDownLatch(1)
        val resource = AnalysisThreadResource(executor, close = {}) {
            proceed.await()
            "detector"
        }

        // The constructor has returned with the factory still parked on the latch.
        // This is the whole point: on device this thread is the one that goes on to
        // call bindToLifecycle.
        assertNull("the build must not have finished on the calling thread", resource.get())

        proceed.countDown()
        drain()
        assertEquals("detector", resource.get())
    }

    @Test
    fun `work queued after construction sees the built resource`() {
        val proceed = CountDownLatch(1)
        val resource = AnalysisThreadResource(executor, close = {}) {
            proceed.await()
            "detector"
        }

        // Stands in for CameraX delivering the first analysis frame: submitted to
        // the same executor while the build is still parked.
        val seenByFrame = AtomicReference<String?>("never ran")
        executor.execute { seenByFrame.set(resource.get()) }

        proceed.countDown()
        drain()

        assertEquals("the first frame must not overtake the build", "detector", seenByFrame.get())
    }

    @Test
    fun `a failing build is reported, not thrown at the caller`() {
        val boom = IllegalStateException("no GPU delegate")
        val resource = AnalysisThreadResource<String>(executor, close = {}) { throw boom }

        drain()

        assertNull("a failed build leaves nothing to use", resource.get())
        assertSame("the reason has to survive for the log", boom, resource.failure)
    }

    @Test
    fun `release closes the resource once, on the executor`() {
        val closed = AtomicInteger(0)
        val closingThread = AtomicReference<Thread?>(null)
        val resource = AnalysisThreadResource(
            executor,
            close = {
                closed.incrementAndGet()
                closingThread.set(Thread.currentThread())
            },
        ) { "detector" }

        // onDispose runs on the main thread; the native handle must not be torn
        // down there while the analysis thread may still be inside detect().
        resource.release()
        drain()

        assertEquals(1, closed.get())
        assertNull("a released holder hands out nothing", resource.get())
        assertNotNull(closingThread.get())
        assertTrue(
            "the resource must be closed on the executor, not the caller",
            closingThread.get() !== Thread.currentThread(),
        )
    }

    /**
     * Leaving the camera screen before the models finish loading is an ordinary
     * cold start on a slow device, and it must not leak the detector: the build
     * task is still queued when `onDispose` runs.
     */
    @Test
    fun `releasing before the build finishes still closes what the build produced`() {
        val proceed = CountDownLatch(1)
        val closed = AtomicInteger(0)
        val resource = AnalysisThreadResource(executor, close = { closed.incrementAndGet() }) {
            proceed.await()
            "detector"
        }

        resource.release()
        proceed.countDown()
        drain()

        assertEquals(1, closed.get())
        assertNull(resource.get())
    }

    @Test
    fun `release after shutdown still closes the resource`() {
        val closed = AtomicInteger(0)
        val resource = AnalysisThreadResource(executor, close = { closed.incrementAndGet() }) { "detector" }
        drain()
        assertEquals("detector", resource.get())

        // shutdown() first, release() second — the reverse of the production order,
        // which is what makes the inline fallback reachable.
        resource.release()

        assertEquals(1, closed.get())
        assertNull(resource.get())
        assertNotNull("the rejection is worth keeping for the log", resource.failure)
    }
}
