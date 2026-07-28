package com.gamdo.app.camera

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * A resource that is **built on [executor], never on the thread that constructs
 * this holder** — and torn down there too.
 *
 * ## The measured problem
 *
 * `CameraScreen` used to build the whole detection stack — three ML Kit clients
 * plus a 4.5MB MediaPipe/TFLite object detector — inside a `remember { }` block,
 * i.e. inline on the Compose composition thread. Composition runs on the main
 * thread, and the `AndroidView` factory that calls [CameraController.bind] sits
 * further down the *same* composable, so **nothing asked CameraX to attach its
 * use cases until every model had finished loading**.
 *
 * On SM-G970N (Android 12, debug, cold start) that was a 6.4s hole: CameraX
 * reported `Added camera: 0` at +1.1s and then `Lifecycle is not set` /
 * `Use cases not attached to camera`, and `PreviewView` did not hand itself to
 * the controller until +7.5s. The preview was black for all of it. The camera was
 * ready the whole time; only the code path to `bindToLifecycle` was blocked.
 *
 * ## Why an executor and not a coroutine
 *
 * The detector has to exist before the first frame is analysed, and the frames
 * arrive on this exact [executor]. A single-threaded executor runs its queue in
 * submission order, so submitting the build **first** *is* the ordering
 * guarantee — no flag to poll, no latch to await, and no window in which a frame
 * can overtake the build. A coroutine on some other dispatcher would need all
 * three, and would have to hand the result across a thread boundary that the
 * detectors are not safe to cross.
 *
 * [executor] must therefore be single-threaded and FIFO. On a pool this degrades
 * gracefully rather than breaking: [get] simply reports null until the build
 * lands, and a caller that gets null skips that frame.
 *
 * Frames that arrive while the build is still running are not a problem for
 * CameraX either — the analysis pipeline is bound `STRATEGY_KEEP_ONLY_LATEST`,
 * which is designed around an analyzer that is slower than the camera, and the
 * preview surface is fed independently of it.
 *
 * @param close how to release [T]. Taken up front rather than passed to
 *   [release] so that a holder released *while the build is still running* can
 *   still dispose of whatever the build goes on to produce.
 */
class AnalysisThreadResource<T : Any>(
    private val executor: Executor,
    private val close: (T) -> Unit,
    factory: () -> T,
) {

    @Volatile
    private var value: T? = null

    /**
     * Why the resource is unavailable, or null. Diagnostic only — the read/write
     * pairs below are not atomic, and nothing branches on this.
     */
    @Volatile
    var failure: Throwable? = null
        private set

    @Volatile
    private var released = false

    init {
        executor.execute {
            val built = runCatching(factory)
                .onFailure { failure = it }
                .getOrNull()
            when {
                built == null -> Unit
                // release() landed between construction and this task. Only
                // reachable on a non-FIFO executor, but a native detector left
                // open is worse than the branch.
                released -> closeQuietly(built)
                else -> value = built
            }
        }
    }

    /**
     * The resource, or null while it is still being built and after [release].
     *
     * Callers on [executor] itself can treat null as "the build failed", because
     * FIFO ordering means the build has already run by the time any later task
     * does.
     */
    fun get(): T? = value

    /**
     * Releases the resource **on [executor]**, so it is torn down on the same
     * thread that built and used it.
     *
     * Safe to call before the build has finished, and safe to call from the main
     * thread — which is the point, because the caller is `onDispose`. Follow it
     * with `shutdown()` (not `shutdownNow()`) so the queued teardown still runs.
     *
     * If the executor has already been shut down the teardown runs inline
     * instead; leaking a native handle to keep a threading rule would be the
     * wrong trade.
     */
    fun release() {
        released = true
        val teardown = Runnable {
            value?.let { closeQuietly(it) }
            value = null
        }
        try {
            executor.execute(teardown)
        } catch (rejected: RejectedExecutionException) {
            if (failure == null) failure = rejected
            teardown.run()
        }
    }

    private fun closeQuietly(resource: T) {
        runCatching { close(resource) }.onFailure { if (failure == null) failure = it }
    }
}
