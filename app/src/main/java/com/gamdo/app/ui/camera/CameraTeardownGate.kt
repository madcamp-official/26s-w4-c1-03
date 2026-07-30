package com.gamdo.app.ui.camera

/**
 * Who releases the camera, and when — the answer the camera screen needs while a
 * capture is still in flight.
 *
 * ## The photo CameraX throws away
 *
 * `CameraScreen`'s `onDispose` used to call `controller.unbind()` unconditionally.
 * That is not a local teardown: `LifecycleCameraController.unbind()` is
 * `ProcessCameraProvider.unbindAll()`, and unbinding an `ImageCapture` runs
 * `ImageCapture.onUnbind()` → `abortImageCaptureRequests()` →
 * `TakePictureManager.abortRequests()`, which walks **both** the queue
 * (`mNewRequests`) **and the requests already in flight** (`mIncompleteRequests`)
 * and fails every one of them with
 * `ImageCaptureException(ERROR_CAMERA_CLOSED, "Camera is closed.")`. The only
 * request it spares is one whose result has already reached the app
 * (`RequestWithCallback.abortAndSendErrorToApp` returns early on
 * `mCompleteFuture.isDone()`).
 *
 * So a shutter press followed within a few hundred milliseconds by a tap on 앨범
 * lost the photo *inside CameraX*, before any code of ours ran — measured
 * `CapturePhase.CAMERA_X` is 290-1613ms, so 0.3s is comfortably inside the window.
 * Reproduced on SM-G970N 2026-07-30, from both directions: the AAR disassembly
 * above, and the device's own
 * `ImageCaptureException: Camera is closed. at TakePictureManager.abortRequests`
 * with zero `CaptureLatency` lines.
 *
 * Making the shutter coroutine uncancellable does not help with that half. The
 * coroutine survives; `capture()` still returns an exception, because the image it
 * was waiting for no longer exists. **The teardown itself has to wait.**
 *
 * ## Why this is a state machine and not an `if`
 *
 * Deferring the unbind creates two failure modes that are worse than the one being
 * fixed, and both are about a release arriving at the wrong moment:
 *
 *  1. **A late release tearing down a live camera.** `unbindAll()` is process-wide.
 *     If the user leaves during a capture and comes straight back, a deferred
 *     release landing after the new screen has bound would unbind *that* screen —
 *     a dead preview, from a code path with no error to show. This is the same
 *     shape as the problem `AnalysisPauseGate.resume(token)` solves with an epoch,
 *     and it is solved the same way here: a release is stamped with the screen
 *     generation that asked for it, and a stale stamp releases nothing.
 *  2. **A release that never arrives.** A capture that never completes would leave
 *     the camera open for the rest of the session — and with it, Android 12's
 *     camera-in-use indicator, on a screen the user has left. Same rule as
 *     `AnalysisPauseGate`: the deferral **expires**, and [expiredDefers] counts it
 *     so the defect is visible rather than merely survivable.
 *
 * Neither can be reproduced under `testDebugUnitTest` through the screen — a
 * `@Composable` cannot be executed on the JVM here (no `androidTest` source set,
 * no Robolectric). So the decision lives in this class, with no `android.*`
 * import, and `CameraTeardownGateTest` drives every ordering by hand.
 *
 * ## The gate never runs anything
 *
 * Every method **returns** the teardown to run instead of invoking it. Two reasons:
 * the caller is on the main thread and this class does not want to promise that,
 * and a lambda invoked while holding [lock] could re-enter. `?.invoke()` at the
 * call site is the whole protocol.
 *
 * @param maxDeferMs how long a release may wait for a capture that has not
 *   finished. Sized against the same span `AnalysisPauseGate` was — the measured
 *   2247ms shutter→saved on SM-G970N — and deliberately **not tighter**, because an
 *   expiry here throws away the very photo this class exists to save. It is a
 *   watchdog for a stuck capture, not a deadline for a slow one.
 */
class CameraTeardownGate(
    val maxDeferMs: Long = DEFAULT_MAX_DEFER_MS,
) {

    init {
        require(maxDeferMs > 0L) { "maxDeferMs must be > 0, was $maxDeferMs" }
    }

    private val maxDeferNs: Long = maxDeferMs * 1_000_000L
    private val lock = Any()

    /**
     * Which camera screen is on screen now.
     *
     * Incremented when one is disposed, so the tokens handed out by
     * [captureStarted] before that point can never be mistaken for the next
     * screen's.
     */
    private var generation: Long = FIRST_GENERATION

    /** Captures started under [generation] and not yet finished. */
    private var inFlightCurrent: Int = 0

    private var deferredTeardown: (() -> Unit)? = null
    private var deferredGeneration: Long = NO_GENERATION
    private var deferredInFlight: Int = 0
    private var deferredDeadlineNs: Long = 0L

    /**
     * How many deferred releases reached [maxDeferMs] with the capture still
     * running.
     *
     * Anything but 0 is a defect — a capture that neither succeeded nor failed —
     * and the photo was lost in that case anyway, so it is logged rather than left
     * to be inferred from a camera indicator that stayed on. Counted once per
     * expiry, not once per poke.
     */
    var expiredDefers: Int = 0
        get() = synchronized(lock) { field }
        private set

    /** True while a release is waiting for a capture. Test/observability only. */
    val hasDeferredTeardown: Boolean
        get() = synchronized(lock) { deferredTeardown != null }

    /**
     * A capture is starting. Returns the token that identifies it to
     * [captureFinished].
     *
     * The token is the screen generation, not a per-capture id: what has to be
     * distinguished is *whose* camera a finishing capture is allowed to release,
     * and two captures from the same screen have the same answer. Overlapping
     * captures are counted, so the release waits for the last of them — the
     * shutter's `capturing` flag already prevents overlap, and this must not
     * *depend* on that.
     */
    fun captureStarted(): Long = synchronized(lock) {
        inFlightCurrent += 1
        generation
    }

    /**
     * That capture is over, however it ended.
     *
     * @return the teardown this capture was holding up, or null — which is the
     *   normal case, and the case for every token that no longer owns anything.
     */
    fun captureFinished(token: Long): (() -> Unit)? = synchronized(lock) {
        when (token) {
            generation -> {
                if (inFlightCurrent > 0) inFlightCurrent -= 1
                null
            }
            deferredGeneration -> {
                if (deferredInFlight > 0) deferredInFlight -= 1
                if (deferredInFlight == 0) takeDeferredLocked() else null
            }
            // A token from a generation whose deferral has already been spent, by
            // the next bind or by the watchdog. It owns nothing; releasing on it
            // would be exactly the "late release tears down a live camera" case.
            else -> null
        }
    }

    /**
     * The camera screen is going away.
     *
     * @param teardown what releases *this* screen's camera. Held, not called.
     * @return the teardown to run right now, or null when it was deferred until
     *   the in-flight capture finishes.
     */
    fun screenDisposed(
        nowNs: Long = System.nanoTime(),
        teardown: () -> Unit,
    ): (() -> Unit)? = synchronized(lock) {
        // At most one deferral is ever pending, because [releaseBeforeBind] spends
        // it before the next screen binds and a screen cannot be disposed before it
        // has bound. Composing rather than asserting anyway: an unreleased camera is
        // the failure this class is here to prevent, and it must not depend on that
        // argument staying true.
        val stale = takeDeferredLocked()

        val release = if (inFlightCurrent > 0) {
            deferredTeardown = teardown
            deferredGeneration = generation
            deferredInFlight = inFlightCurrent
            deferredDeadlineNs = nowNs + maxDeferNs
            null
        } else {
            teardown
        }

        inFlightCurrent = 0
        generation += 1
        andThen(stale, release)
    }

    /**
     * Spend any deferred release **now**, because a camera is about to be bound.
     *
     * This is the guard that makes deferral safe at all. `unbindAll()` does not
     * know which controller asked for it, so a deferral that outlived its screen
     * would tear down whatever is bound when it finally runs. Calling this
     * immediately before the new bind — and never after it — means the two can
     * never overlap: the old camera is always released while the new one does not
     * yet exist.
     *
     * The capture that deferral was waiting for is *not* waited for. It is already
     * lost the moment a second camera needs the hardware, and a photo is worth less
     * than a working preview.
     */
    fun releaseBeforeBind(): (() -> Unit)? = synchronized(lock) { takeDeferredLocked() }

    /**
     * The watchdog: a deferral past its deadline is not a deferral.
     *
     * Poked by a delayed main-thread post from the screen that deferred, because
     * once the analyzer is detached there is no frame loop left to ask — unlike
     * `AnalysisPauseGate`, which gets its expiry check for free on every frame.
     */
    fun releaseIfExpired(nowNs: Long = System.nanoTime()): (() -> Unit)? = synchronized(lock) {
        if (deferredTeardown == null) return null
        // Subtraction rather than `nowNs > deadline`, so a nanoTime origin that
        // straddles Long.MAX_VALUE does not read as "not yet".
        if (nowNs - deferredDeadlineNs < 0L) return null
        expiredDefers += 1
        takeDeferredLocked()
    }

    private fun takeDeferredLocked(): (() -> Unit)? {
        val teardown = deferredTeardown ?: return null
        deferredTeardown = null
        deferredGeneration = NO_GENERATION
        deferredInFlight = 0
        deferredDeadlineNs = 0L
        return teardown
    }

    /** Composes two optional teardowns into the one the caller has to run. */
    private fun andThen(first: (() -> Unit)?, second: (() -> Unit)?): (() -> Unit)? = when {
        first == null -> second
        second == null -> first
        else -> ({ first(); second() })
    }

    companion object {

        /** Before any screen has been disposed. Never a valid deferral stamp. */
        const val FIRST_GENERATION = 1L

        /** The stamp on "nothing is deferred"; no token can equal it. */
        const val NO_GENERATION = 0L

        /**
         * 4s.
         *
         * The same figure as [com.gamdo.app.camera.AnalysisPauseGate.DEFAULT_MAX_PAUSE_MS],
         * for the same reason and against the same measurement: shutter→saved was
         * 2247ms on SM-G970N and `CapturePhase.CAMERA_X` alone reached 1613ms, so a
         * working capture never approaches this while a wedged one cannot outlast
         * it. It is not tuned down towards "the indicator should go off sooner":
         * every millisecond cut off here is a millisecond in which a real photo gets
         * thrown away, which is the bug, not the fix.
         */
        const val DEFAULT_MAX_DEFER_MS = 4_000L
    }
}

/**
 * The process's one camera teardown gate.
 *
 * Deliberately not `remember`ed in the camera screen: its entire job is to be the
 * one thing a *departing* screen and an *arriving* screen both see, and a value
 * remembered by either would be invisible to the other. There is one camera, so
 * there is one of these.
 */
internal val cameraTeardownGate = CameraTeardownGate()
