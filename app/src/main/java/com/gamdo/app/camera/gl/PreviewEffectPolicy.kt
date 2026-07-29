package com.gamdo.app.camera.gl

/**
 * Where the preview colour effect is in its life, as far as the fallback cares.
 *
 * [OFF] covers both "not started" and "cleanly released" — neither is a fault, and
 * the difference is not something a caller has to act on.
 */
enum class PreviewColorState { OFF, ATTACHING, RUNNING, DETACHED }

/** Why colour is not on the preview. Exactly one of these reaches the log. */
enum class PreviewColorOffReason {
    /** EGL, shader compilation or program linking failed. The effect never attached. */
    SETUP_FAILED,

    /** CameraX reported a `ProcessingException` out of our `SurfaceProcessor`. */
    PROCESSING_ERROR,

    /** Our own draw threw. */
    DRAW_FAILED,

    /**
     * Attached, everything returned success, and no frame was ever drawn.
     *
     * This is the shape W3-4 recorded on this device: `glMapBufferRange` fails with
     * `GL_INVALID_VALUE` and the pipeline goes quiet rather than throwing. Nothing
     * to catch, so it has to be timed.
     */
    NO_FIRST_FRAME,
}

/** What the caller should do about the event it just reported. */
sealed interface PreviewEffectDecision {
    /** Nothing to do. */
    data object Continue : PreviewEffectDecision

    /** Remove the effect and log [reason]. Emitted at most once per policy. */
    data class Detach(val reason: PreviewColorOffReason) : PreviewEffectDecision

    /** Already detached; the caller has already done the work. */
    data object AlreadyOff : PreviewEffectDecision
}

/**
 * The rule that keeps a GL failure from taking the preview down with it.
 *
 * ## Why this is a class and not four `if`s at the call site
 *
 * The events arrive on three different threads — CameraX's error listener, our GL
 * thread, and a main-thread watchdog — and the one thing that must not happen is
 * two of them deciding to detach. `clearEffects()` rebinds the camera; running it
 * twice is a second black flash on a screen that is already failing. So the
 * decision is funnelled through one object that emits [PreviewEffectDecision.Detach]
 * exactly once, and the call site's whole job is to obey it.
 *
 * It is also the only piece of O-14 a JVM test can reach. See
 * `PreviewEffectPolicyTest` for what "untestable" means for the rest.
 *
 * ## Not thread-safe on purpose
 *
 * Callers funnel every event onto one thread (the GL executor) before calling in.
 * A lock here would suggest the rest of the pipeline is safe to touch from
 * anywhere, which it is not — an EGL context is bound to the thread that made it.
 *
 * @param firstFrameDeadlineMs how long after attaching a first drawn frame may take
 *   before the effect is presumed dead. Generous: this covers surface allocation and
 *   the first camera frame on a cold start, not a steady-state frame interval.
 */
class PreviewEffectPolicy(
    private val firstFrameDeadlineMs: Long = DEFAULT_FIRST_FRAME_DEADLINE_MS,
) {

    var state: PreviewColorState = PreviewColorState.OFF
        private set

    /** Set when and only when [state] is [PreviewColorState.DETACHED]. */
    var offReason: PreviewColorOffReason? = null
        private set

    private var attachedAtMs: Long = 0L

    /** Setup failed before CameraX was told anything. The cheapest failure there is. */
    fun onSetupFailed(): PreviewEffectDecision = detach(PreviewColorOffReason.SETUP_FAILED)

    /** The effect has been handed to CameraX. Arms the first-frame deadline. */
    fun onAttached(nowMs: Long): PreviewEffectDecision {
        if (state == PreviewColorState.DETACHED) return PreviewEffectDecision.AlreadyOff
        state = PreviewColorState.ATTACHING
        attachedAtMs = nowMs
        return PreviewEffectDecision.Continue
    }

    /** A frame reached the output surface. Disarms the deadline permanently. */
    fun onFrameDrawn(): PreviewEffectDecision {
        if (state == PreviewColorState.DETACHED) return PreviewEffectDecision.AlreadyOff
        state = PreviewColorState.RUNNING
        return PreviewEffectDecision.Continue
    }

    fun onProcessingError(): PreviewEffectDecision = detach(PreviewColorOffReason.PROCESSING_ERROR)

    fun onDrawFailed(): PreviewEffectDecision = detach(PreviewColorOffReason.DRAW_FAILED)

    /**
     * Time passed. Only meaningful between [onAttached] and the first
     * [onFrameDrawn]; outside that window it is deliberately inert, so a caller can
     * poll on a fixed schedule without knowing the state.
     */
    fun onTick(nowMs: Long): PreviewEffectDecision {
        if (state == PreviewColorState.DETACHED) return PreviewEffectDecision.AlreadyOff
        if (state != PreviewColorState.ATTACHING) return PreviewEffectDecision.Continue
        if (nowMs - attachedAtMs <= firstFrameDeadlineMs) return PreviewEffectDecision.Continue
        return detach(PreviewColorOffReason.NO_FIRST_FRAME)
    }

    /**
     * Leaving the camera screen. Resets to [PreviewColorState.OFF] rather than to
     * DETACHED: a clean teardown is not a fault, and reporting it as one would bury
     * the single log line that says this device cannot run the effect.
     */
    fun onReleased() {
        state = PreviewColorState.OFF
        offReason = null
        attachedAtMs = 0L
    }

    private fun detach(reason: PreviewColorOffReason): PreviewEffectDecision {
        if (state == PreviewColorState.DETACHED) return PreviewEffectDecision.AlreadyOff
        state = PreviewColorState.DETACHED
        offReason = reason
        return PreviewEffectDecision.Detach(reason)
    }

    companion object {
        /**
         * 2.5s. Long enough to cover surface allocation plus a cold-start first
         * frame on this device (W3-4 measured 6.7s of *detector* build before that
         * work moved off the critical path; the preview surface itself was well
         * inside a second), short enough that a user staring at a dead preview gets
         * it back before deciding the app is broken.
         *
         * Unmeasured on hardware — see the DONE-DEVICE list.
         */
        const val DEFAULT_FIRST_FRAME_DEADLINE_MS = 2_500L
    }
}
