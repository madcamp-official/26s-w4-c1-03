package com.gamdo.app.detect

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A value that is only ever touched on **one** thread, whoever asks.
 *
 * ## What it is for
 *
 * The upgraded GPU object detector (see [GpuUpgradePolicy]) is built on a
 * background thread, because a 7.5s TFLite GPU delegate compilation on the CameraX
 * analysis executor would move the cold-start stall rather than remove it. It is
 * then used from the analysis thread, once per frame.
 *
 * Whether MediaPipe Tasks tolerates that split was the open question. The evidence
 * available in `tasks-core-0.10.26` is suggestive but not normative:
 * `TaskRunner.process(Map)` is declared `synchronized`, and its bytecode is
 * `addPackets(…)` → `Graph.waitUntilGraphIdle()` → read the cached result — the
 * calling thread pushes packets across JNI and blocks while the MediaPipe graph
 * runs the calculators, and therefore the TFLite GPU delegate's GL context, on
 * scheduler threads of its own. Nothing in the public API or in that call path
 * *promises* thread-agnostic use, and the failure mode of guessing wrong is a
 * native crash on hardware we cannot reproduce. So the detector is confined.
 *
 * Confinement also buys a second thing that no confidence level about the first
 * could: on SM-G970N the GPU delegate is *created* successfully and its *first
 * inference* fails, so the upgrade is only adopted after a validation inference —
 * and a validation inference is evidence about the thread that ran it. Confining
 * makes that the same thread the production inferences use.
 *
 * ## Contract
 *
 * Construct on [confinement]'s own thread — [confinedTo] records
 * `Thread.currentThread()` as the owner. Calls from the owner run **inline**, which
 * is what lets the validation inference happen inside the build task without
 * deadlocking a single-thread executor against itself; calls from anywhere else
 * marshal and block.
 *
 * The cost at the call site is one thread hop per frame, against a GPU inference
 * measured in tens of milliseconds.
 *
 * [unconfined] is the CPU detector: built on the analysis thread, used there, no
 * hop. Same type so the per-frame path has one shape rather than two.
 */
class ThreadConfined<T : Any> private constructor(
    private val value: T,
    private val confinement: ExecutorService?,
    private val owner: Thread?,
) {

    private val closed = AtomicBoolean(false)

    /** The thread this value lives on, or null when it is not confined. For logs. */
    val ownerName: String? get() = owner?.name

    /**
     * Runs [block] on the owner thread and returns its result.
     *
     * Anything [block] throws is rethrown **as itself** — the caller branches on the
     * throwable and records its class name, and an `ExecutionException` wrapper
     * would name the marshalling instead of the GL fault.
     */
    fun <R> use(block: (T) -> R): R {
        check(!closed.get()) { "value has been closed" }
        val executor = confinement ?: return block(value)
        if (Thread.currentThread() === owner) return block(value)
        try {
            return executor.submit(Callable { block(value) }).get()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (failed: ExecutionException) {
            throw failed.cause ?: failed
        }
    }

    /**
     * Releases the value **on the owner thread**, then lets that thread finish.
     *
     * Idempotent, and deliberately so: the analysis thread closes on teardown while
     * the upgrade thread closes a detector it has just decided not to publish, and
     * the two can race. Closing a MediaPipe handle twice is a native double free.
     */
    fun close(release: (T) -> Unit) {
        if (!closed.compareAndSet(false, true)) return
        val executor = confinement
        if (executor == null || Thread.currentThread() === owner) {
            runCatching { release(value) }
            executor?.shutdown()
            return
        }
        // Rejected means the thread is already gone, and a native handle left open
        // is worse than a threading rule kept, so fall back to releasing inline.
        runCatching { executor.submit { runCatching { release(value) } }.get() }
            .onFailure { runCatching { release(value) } }
        executor.shutdown()
    }

    companion object {
        /** For a value built and used on the caller's own thread. No hop, no owner. */
        fun <T : Any> unconfined(value: T): ThreadConfined<T> = ThreadConfined(value, null, null)

        /**
         * Confines [value] to [executor]. **Call from [executor]'s own thread**, so
         * that the thread which built the value is the thread that owns it.
         */
        fun <T : Any> confinedTo(executor: ExecutorService, value: T): ThreadConfined<T> =
            ThreadConfined(value, executor, Thread.currentThread())
    }
}
