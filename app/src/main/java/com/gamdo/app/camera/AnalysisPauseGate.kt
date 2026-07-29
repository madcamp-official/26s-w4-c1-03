package com.gamdo.app.camera

/**
 * Whether the detection stack may run right now — the one question
 * [FrameAnalyzer] asks before doing 200-300ms of work.
 *
 * ## Why a capture pauses analysis
 *
 * The guide is not used while the shutter is firing: the overlay's last state is
 * already what the user framed against, and §3-3 reads `lastFrame` *before*
 * awaiting the capture on purpose. Meanwhile the analysis pipeline is the app's
 * heaviest CPU consumer (measured 2026-07-29: 178-405ms per frame, object
 * detection alone 86-206ms of it, on a thread that also drags TFLite's and ML
 * Kit's own worker threads along). Handing those cores to CameraX and to the
 * full-resolution decode for the length of one capture is free of any cost to the
 * guide.
 *
 * ## What it actually bought, measured
 *
 * **Nothing detectable on SM-G970N.** 23 instrumented captures, 2026-07-29, both
 * settings at a matched cold start (AP 40.6°C vs 41.5°C), 5 samples each — the
 * app-side stages (`decode`+`crop`+`encode`+`appFile`+`row`, the only ones free of
 * 3A variance) came to **1213ms paused off, 1203ms paused on**. A 10ms difference,
 * well inside the noise.
 *
 * The mechanism explains it: the decode already fans out across
 * `Dispatchers.Default`, so the analysis thread is one of eight cores. Handing back
 * one core is ~12% at the theoretical best, and the theoretical best is not what
 * happens.
 *
 * Do not trust `total=` for this comparison. It is dominated by `cameraX`, which
 * ranged 290-1613ms *within one configuration* — the flat, dim test scene left 3A
 * hunting. Two runs of the identical build from an identical cold start gave
 * medians of 1678ms and 2687ms, a swing larger than any effect being measured.
 *
 * It is kept, on the owner's call (2026-07-29), against the possibility that a
 * device with fewer or slower cores gets more out of it than this one did. That is
 * a hypothesis, not a result, and it should be labelled as one until someone
 * measures it on such a device. **If this class ever costs anything — a stuck
 * pause, a flickering guide, an awkward interaction with
 * `SceneDetector.setObjectDetectionPaused` — delete it rather than repair it.** It
 * is not paying for itself here.
 *
 * ## Skip, never release
 *
 * This gate makes [FrameAnalyzer] **drop frames**. It does not touch the detector
 * or the analysis executor, and it must never be made to: W3-4 spent the effort
 * that made the detector expensive to build (CPU-first construction, background
 * GPU upgrade, a process-scoped lease that survives a trip to the album), and a
 * "pause" that released it would charge that back on every shutter press.
 *
 * ## The failure that actually matters
 *
 * A slow shutter is an annoyance; a pause that never lifts is a camera whose
 * guide is dead for the rest of the session, with nothing on screen to say so. So
 * the gate is asymmetric on purpose — it fails towards RUNNING every way it can:
 *
 *  1. [resume] is called from the shutter coroutine's `finally`, which runs on
 *     success, on failure and on cancellation alike.
 *  2. [resumeAll] is called when the camera screen is disposed, which covers the
 *     coroutine that was cancelled before its `finally` could run and the user
 *     who navigated away mid-capture.
 *  3. **[isPaused] expires the pause itself.** The analysis thread does not trust
 *     anyone to have called anything: a pause older than [maxPauseMs] is not a
 *     pause. This is the guarantee that survives a future edit adding an early
 *     `return` above the `finally`, and it is what
 *     `AnalysisPauseGateTest.a pause whose resume never arrives expires on its own`
 *     pins.
 *
 * Resuming *early* is the harmless direction and the gate takes it whenever the
 * two directions conflict — see [resume]'s handling of overlapping captures.
 *
 * @param enabled false disables pausing entirely, so the same build can be
 *   measured with and without. See [CapturePauseTuning.PAUSE_ANALYSIS_DURING_CAPTURE].
 * @param maxPauseMs how long a pause may live unresumed. Sized against the
 *   measured capture (2247ms shutter→file on SM-G970N, 2026-07-29): high enough
 *   that a normal capture never trips it, low enough that a stuck one costs a few
 *   frames of guide rather than a session.
 */
class AnalysisPauseGate(
    val isEnabled: Boolean = CapturePauseTuning.PAUSE_ANALYSIS_DURING_CAPTURE,
    maxPauseMs: Long = DEFAULT_MAX_PAUSE_MS,
) {

    init {
        require(maxPauseMs > 0L) { "maxPauseMs must be > 0, was $maxPauseMs" }
    }

    private val maxPauseNs: Long = maxPauseMs * 1_000_000L
    private val lock = Any()

    /** When the current pause started, or null when analysis is running. */
    private var pausedSinceNs: Long? = null

    /** Identifies the pause in flight, so a late [resume] cannot release a newer one. */
    private var epoch: Long = NO_LEASE

    /**
     * How many pauses reached [maxPauseMs] without being resumed.
     *
     * Anything but 0 is a defect — one of the two resume paths did not run — and
     * it is logged at the next shutter rather than left for someone to notice that
     * the guide flickers. Counted once per stuck pause, not once per frame that
     * finds it stuck.
     */
    var watchdogTrips: Int = 0
        get() = synchronized(lock) { field }
        private set

    /**
     * A capture is starting. Returns the token that releases it.
     *
     * Returns [NO_LEASE] when disabled; [resume] then has nothing to do, which is
     * why the caller never has to branch on [isEnabled].
     */
    fun pause(nowNs: Long = System.nanoTime()): Long = synchronized(lock) {
        if (!isEnabled) return NO_LEASE
        epoch += 1
        pausedSinceNs = nowNs
        return epoch
    }

    /**
     * That capture is over, however it ended.
     *
     * Only the newest token releases. With two captures overlapping — which the
     * `capturing` flag on the shutter already prevents, but which this must not
     * *depend* on — the two possible mistakes are not symmetric: releasing on the
     * older token would resume analysis while a capture is still in flight (a
     * slower shot), whereas ignoring it means the still-running capture's own
     * `finally` releases a moment later (nothing). Idempotent, because the
     * shutter's `finally` and the screen's `onDispose` can both fire for one
     * capture.
     */
    fun resume(token: Long) = synchronized(lock) {
        if (token == epoch) pausedSinceNs = null
    }

    /**
     * Release whatever is held, whoever holds it — camera screen teardown.
     *
     * Unconditional because there is no token to hand back here: the coroutine
     * that owned it was cancelled.
     */
    fun resumeAll() = synchronized(lock) {
        pausedSinceNs = null
    }

    /**
     * Asked once per frame on the analysis thread.
     *
     * Expires the pause rather than merely reporting it: see the class KDoc,
     * guarantee 3.
     */
    fun isPaused(nowNs: Long = System.nanoTime()): Boolean = synchronized(lock) {
        val since = pausedSinceNs ?: return false
        if (nowNs - since < maxPauseNs) return true
        pausedSinceNs = null
        watchdogTrips += 1
        return false
    }

    companion object {
        /** What [pause] returns when the gate is disabled: a token that releases nothing. */
        const val NO_LEASE = 0L

        /**
         * 4s — about 1.8× the measured 2247ms shutter→saved, before this change
         * shortened it. A capture that has not finished in four seconds has failed
         * in some way this class cannot see, and the guide is worth more than the
         * remaining CPU.
         */
        const val DEFAULT_MAX_PAUSE_MS = 4_000L
    }
}

/**
 * The one switch for change (1), kept so the same source tree can produce the
 * before and after measurement with a one-character edit rather than a revert.
 *
 * It is not a config-asset value: `guide_config.json`'s parser lives in `guide/`,
 * which this vertical does not own. If the lead wants it externalised the key is
 * `features.pauseAnalysisDuringCapture` and it needs one line in
 * `FeaturesConfigJson`.
 */
object CapturePauseTuning {

    /** Flip to false to measure a capture with the detection stack still running. */
    const val PAUSE_ANALYSIS_DURING_CAPTURE = true
}
