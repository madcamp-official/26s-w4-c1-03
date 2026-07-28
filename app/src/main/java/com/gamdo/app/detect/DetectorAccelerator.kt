package com.gamdo.app.detect

/**
 * Which processor an on-device model actually ended up running on.
 *
 * Deliberately *not* MediaPipe's `Delegate`. This type is the app's own record of
 * an outcome, it is read by JVM tests and (through [AcceleratorReporting]) by the
 * debug HUD, and neither should have to load the MediaPipe runtime to ask a
 * one-word question. The mapping to `Delegate` lives at the one place that owns
 * the detector, [EfficientDetSceneDetector].
 */
enum class DetectorAccelerator { GPU, CPU }

/**
 * What actually happened when the object detector picked an accelerator.
 *
 * ## Why this type exists
 *
 * `EfficientDetSceneDetectorConfig.preferGpu` defaults to true and
 * `guide_config.json` sets it true, so the app asks for GPU on every launch. On
 * device it does not get it: the logcat around detector init reads
 *
 * ```
 * I tflite: Created TensorFlow Lite XNNPACK delegate for CPU.
 * ```
 *
 * XNNPACK is the CPU path, and the GPU counterpart —
 * `"Created TensorFlow Lite delegate for GPU."`, a string that is present in the
 * shipped `libmediapipe_tasks_vision_jni.so` — never appears. So GPU init fails
 * and the app silently runs the heaviest per-frame model on the CPU.
 *
 * The old code could not have told anyone. Delegate selection was a
 * `runCatching { return createDetector(context, delegate) }` loop whose failure
 * went into a local `lastFailure` that was thrown away as soon as a later
 * delegate succeeded, and the delegate that won was stored in a private field no
 * log or caller ever read. The single fact that decides whether the object stage
 * costs tens or hundreds of milliseconds was inferable only from a third-party
 * log line about node counts.
 *
 * A downgrade is not a bug in itself — CPU is the correct answer on a device
 * whose driver cannot serve the model. Silence is the bug. This record is
 * printed once at init and stays readable afterwards.
 *
 * @param requestedGpu what the config asked for, so a reader can see the gap
 *   between intent and outcome without opening `guide_config.json`.
 * @param accelerator what the detector is actually running on; `null` means the
 *   detector never initialised at all and object detection is off this session.
 * @param gpuFailure the reason GPU was refused, as text. The throwable itself is
 *   handed to `Log.w` separately; this copy is what survives into the HUD.
 * @param runtimeDowngrade true when GPU initialised and then failed on a later
 *   inference, so the detector was rebuilt on CPU mid-session. Distinct from a
 *   failure at init: some Samsung drivers accept the delegate and fault later on
 *   a GL buffer map, and the two cases point at different investigations.
 */
data class DetectorAcceleratorReport(
    val requestedGpu: Boolean,
    val accelerator: DetectorAccelerator?,
    val gpuFailure: String? = null,
    val runtimeDowngrade: Boolean = false,
) {
    /**
     * GPU was asked for and something else is running.
     *
     * Also true when [accelerator] is `null`: a detector that failed to
     * initialise is not "not degraded", it is the worst outcome available.
     */
    val degraded: Boolean = requestedGpu && accelerator != DetectorAccelerator.GPU

    /** One line, greppable, safe to print on any build. */
    fun format(): String = buildString {
        append("objectDetector accelerator=")
        append(accelerator?.name ?: "none")
        append(" requested=")
        append(if (requestedGpu) "GPU" else "CPU")
        append(" degraded=")
        append(degraded)
        if (runtimeDowngrade) append(" runtimeDowngrade=true")
        gpuFailure?.let {
            append(" gpuError=")
            append(it)
        }
    }

    companion object {
        /**
         * The order delegates are tried in.
         *
         * Kept here rather than inline in the detector so the policy is testable
         * without a `Context`, a 4.5MB TFLite asset and the MediaPipe native
         * runtime — none of which exist on the JVM.
         *
         * `preferGpu = false` yields CPU **only**, not CPU-then-GPU. Turning the
         * preference off is an instruction to stop asking, not a reordering.
         */
        fun plan(preferGpu: Boolean): List<DetectorAccelerator> = if (preferGpu) {
            listOf(DetectorAccelerator.GPU, DetectorAccelerator.CPU)
        } else {
            listOf(DetectorAccelerator.CPU)
        }
    }
}

/**
 * A detector that resolves an accelerator at init and can say which one it got.
 *
 * Implemented by [EfficientDetSceneDetector] and forwarded by
 * [ThrottledObjectSceneDetector], so the debug HUD can read the outcome through
 * whatever wrapper stack `SceneDetector` was built with. Returning `null` is the
 * honest answer for a detector that has no accelerator to report — the interface
 * exists so callers do not have to know which concrete class is underneath.
 */
interface AcceleratorReporting {
    val acceleratorReport: DetectorAcceleratorReport?
}
