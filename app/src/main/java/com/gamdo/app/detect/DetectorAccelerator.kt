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
 * `guide_config.json` sets it true, so the app asks for GPU on every launch, and on
 * SM-G970N it ends up on the CPU anyway. The old code could not have told anyone
 * why. Delegate selection was a `runCatching { return createDetector(context,
 * delegate) }` loop whose failure went into a local `lastFailure` that was thrown
 * away as soon as a later delegate succeeded, and the delegate that won was stored
 * in a private field no log or caller ever read. The single fact that decides
 * whether the object stage costs tens or hundreds of milliseconds was inferable
 * only from a third-party log line about node counts.
 *
 * A downgrade is not a bug in itself — CPU is the correct answer on a device whose
 * driver cannot serve the model. Silence is the bug. This record is printed once at
 * init, again at every transition, and stays readable afterwards.
 *
 * ## What the record was wrong about, and what fixed it
 *
 * This KDoc used to argue that GPU *initialisation* fails on device, from the
 * absence of `"Created TensorFlow Lite delegate for GPU."` in logcat. **Disproven,
 * 2026-07-29.** Once the record above actually printed, it read
 * `objectDetector accelerator=GPU requested=GPU degraded=false` — the delegate
 * initialises. It is the *first inference* that fails, with
 * `[GL_INVALID_VALUE]: glMapBufferRange` out of `gl_interop.cc`, 3/3 reproducible.
 * The absent log line proved nothing; a third-party library is under no obligation
 * to print.
 *
 * That distinction is why [upgrade] exists: creation succeeding is not evidence the
 * delegate works, so the GPU is now built after the CPU one and adopted only once a
 * real inference on it has returned. See [GpuUpgradePolicy].
 *
 * @param requestedGpu what the config asked for, so a reader can see the gap
 *   between intent and outcome without opening `guide_config.json`.
 * @param accelerator what the detector is actually running on; `null` means the
 *   detector never initialised at all and object detection is off this session.
 * @param gpuFailure the reason GPU was refused, as text. The throwable itself is
 *   handed to `Log.w` separately; this copy is what survives into the HUD.
 * @param runtimeDowngrade true when the GPU served frames and then failed on a
 *   later inference, so the detector fell back to CPU mid-session. Distinct from a
 *   refusal before adoption: one means a delegate that worked and stopped, the
 *   other a delegate that never started, and they point at different
 *   investigations.
 * @param upgrade how the session got to [accelerator] — the part `accelerator=CPU`
 *   cannot say on its own. See [GpuUpgradeStage].
 */
data class DetectorAcceleratorReport(
    val requestedGpu: Boolean,
    val accelerator: DetectorAccelerator?,
    val gpuFailure: String? = null,
    val runtimeDowngrade: Boolean = false,
    val upgrade: GpuUpgradeStage = GpuUpgradeStage.NOT_REQUESTED,
) {
    /**
     * GPU was asked for and something else is running.
     *
     * Also true when [accelerator] is `null`: a detector that failed to
     * initialise is not "not degraded", it is the worst outcome available.
     */
    val degraded: Boolean = requestedGpu && accelerator != DetectorAccelerator.GPU

    /**
     * The GPU build survived a validation inference and is taking over.
     *
     * Clears [gpuFailure]: a report that names a working accelerator and a reason it
     * was refused describes two different sessions.
     */
    fun adoptingGpu(): DetectorAcceleratorReport = copy(
        accelerator = DetectorAccelerator.GPU,
        gpuFailure = null,
        upgrade = GpuUpgradeStage.ADOPTED,
    )

    /**
     * The upgrade was refused before it ever served a frame. CPU keeps serving.
     *
     * [runtimeDowngrade] deliberately stays false — nothing was running on the GPU
     * to fall back *from*, and conflating the two would hide the case where a
     * working delegate died mid-session.
     *
     * @param stage which step refused: [GpuUpgradeStage.CREATE_FAILED] or
     *   [GpuUpgradeStage.VALIDATION_FAILED].
     */
    fun refusingGpu(stage: GpuUpgradeStage, failure: String?): DetectorAcceleratorReport {
        require(!stage.adopted) { "a refusal cannot record an adoption: $stage" }
        return copy(
            accelerator = DetectorAccelerator.CPU,
            gpuFailure = failure,
            upgrade = stage,
        )
    }

    /** An adopted GPU faulted mid-session. CPU takes over and is not upgraded again. */
    fun revokingGpu(failure: String?): DetectorAcceleratorReport = copy(
        accelerator = DetectorAccelerator.CPU,
        gpuFailure = failure,
        runtimeDowngrade = true,
        upgrade = GpuUpgradeStage.REVOKED,
    )

    /**
     * One line, greppable, safe to print on any build.
     *
     * `gpuUpgrade=` is unconditional because its whole job is to separate outcomes
     * that otherwise print identically — `accelerator=CPU requested=GPU
     * degraded=true` is the same line whether the delegate was never built, could
     * not be built, could not infer, or died after an hour. [gpuFailure] stays last
     * because it is free text containing spaces, so nothing after it is parseable.
     */
    fun format(): String = buildString {
        append("objectDetector accelerator=")
        append(accelerator?.name ?: "none")
        append(" requested=")
        append(if (requestedGpu) "GPU" else "CPU")
        append(" degraded=")
        append(degraded)
        append(" gpuUpgrade=")
        append(upgrade.name)
        if (runtimeDowngrade) append(" runtimeDowngrade=true")
        gpuFailure?.let {
            append(" gpuError=")
            append(it)
        }
    }

    companion object {
        /**
         * Which delegates this config is willing to end up on, best first.
         *
         * **Not the order they are built in, and no longer the cold-start
         * sequence.** Walking this list at construction is what cost SM-G970N 7570ms
         * of GPU delegate compilation before the first frame could be analysed —
         * for a delegate whose first inference then threw. The cold start now builds
         * on [GpuUpgradePolicy.coldStart] and the GPU is pursued afterwards, off the
         * critical path; this list is what [GpuUpgradePolicy.initialStage] reads to
         * decide whether pursuing it is wanted at all.
         *
         * Kept here rather than inline in the detector so the policy is testable
         * without a `Context`, a 4.5MB TFLite asset and the MediaPipe native
         * runtime — none of which exist on the JVM.
         *
         * `preferGpu = false` yields CPU **only**. Turning the preference off is an
         * instruction to stop asking, not a reordering.
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
