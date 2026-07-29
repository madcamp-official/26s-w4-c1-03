package com.gamdo.app.detect

/**
 * Where the GPU delegate stands this session.
 *
 * Separate from [DetectorAccelerator], which says what is running *now*. These say
 * how it got there, which is the part a reader cannot reconstruct: `accelerator=CPU`
 * alone does not distinguish "the config never asked for GPU" from "GPU was asked
 * for, built, and could not infer".
 *
 * @param adopted whether frames are served on the GPU in this stage. Exactly one
 *   stage says yes, and the per-frame path reads this rather than comparing names.
 */
enum class GpuUpgradeStage(val adopted: Boolean) {
    /** `preferGpu` is off, or there was no CPU detector to upgrade from. */
    NOT_REQUESTED(false),

    /** The background attempt is running, or about to. CPU is serving frames. */
    PENDING(false),

    /** Built **and** validated by a real inference. The GPU is serving frames. */
    ADOPTED(true),

    /** `ObjectDetector.createFromOptions` threw for the GPU delegate. CPU keeps serving. */
    CREATE_FAILED(false),

    /**
     * The delegate was created and the validation inference threw.
     *
     * This is SM-G970N's outcome, and the reason creation is not the gate: on that
     * device `createFromOptions` returns a usable-looking handle, `accelerator=GPU
     * degraded=false` is logged, and the first `detect()` fails with
     * `[GL_INVALID_VALUE]: glMapBufferRange` out of `gl_interop.cc`.
     */
    VALIDATION_FAILED(false),

    /**
     * Adopted, served frames, then faulted. CPU took over for the rest of the
     * session and the GPU is not attempted again.
     */
    REVOKED(false),
}

/**
 * **CPU 선행 + GPU 후행 검증** — when the object detector may try the GPU delegate,
 * what counts as the GPU working, and what happens when it does not.
 *
 * ## The measurement this exists for (SM-G970N, 2026-07-29)
 *
 * ```
 * 10:51:29.590 I/EfficientDet: objectDetector accelerator=GPU requested=GPU degraded=false
 * 10:51:29.606 D/CameraStartup: detectorBuild face=20 pose=25 object=7570 seg=15 total=7630ms
 * 10:51:31.320 W/EfficientDet: GPU inference failed mid-session — rebuilding on CPU
 * 10:51:31.431 W/EfficientDet: accelerator=CPU degraded=true runtimeDowngrade=true
 * ```
 *
 * Cold start spent **7.6 seconds building a detector that was discarded 1.7s
 * later**, because the delegate compiled fine and the first inference did not run.
 * Reproducible 3/3.
 *
 * The obvious suspect — reading a 4.5MB TFLite asset on a cold page cache — is not
 * it. Forcing CPU-only in an isolated worktree, cold process, twice:
 *
 * | | object | total |
 * |---|---|---|
 * | GPU first (old) | 7570 · 7933ms | 7630 · 7992ms |
 * | CPU only (cold) | **281 · 228ms** | **376 · 313ms** |
 *
 * Same asset, same cold process, ~250ms. **The 7.5s is GPU delegate compilation.**
 *
 * ## Why the answer is not `preferGpu = false`
 *
 * GPU works in 담당 B's environment (owner, 2026-07-29). A global flag would delete
 * a working fast path on hardware that has one, to fix a driver bug on hardware
 * that does not. Nor is a per-device record the answer: a boolean "this device
 * failed once" turns a transient refusal — GPU memory pressure, another app holding
 * GL, a driver hiccup — into a permanent downgrade (owner, 2026-07-29).
 *
 * So the fix is **ordering**, and it needs no device memory at all:
 *
 *  1. cold start builds on [coldStart], i.e. CPU, on every device — the guide
 *     arrives in ~350ms instead of ~8s;
 *  2. if the config wanted GPU, one background attempt runs off the critical path;
 *  3. it is adopted only if a real inference on it returns. Creation succeeding
 *     proves nothing — that is precisely the trap this device demonstrates;
 *  4. on failure the GPU detector is closed, CPU keeps serving, the outcome is
 *     recorded, and **it is never retried**.
 *
 * Both environments win: this device stops paying 7.5s for a delegate it cannot
 * use, and 담당 B's device gets the same 7.5s off its cold start while still ending
 * up on the GPU a moment later.
 *
 * Pure Kotlin, no `android.*`, for the reason [DetectorAcceleratorReport.plan] and
 * `DetectorWarmupGate` are: the decisions are testable and the MediaPipe runtime is
 * not.
 */
object GpuUpgradePolicy {

    /**
     * What the **cold-start** build runs on: CPU, unconditionally.
     *
     * Not a function of `preferGpu`. The preference decides whether the GPU is
     * *pursued*, never whether the first detector waits for it — on a device where
     * the delegate compiles in 7.5s and then does not work, waiting is the entire
     * bug, and on a device where it works the user still waits 7.5s for a guide the
     * CPU could have drawn in 350ms.
     */
    val coldStart: DetectorAccelerator = DetectorAccelerator.CPU

    /**
     * The stage a freshly built detector starts in.
     *
     * @param preferGpu the config's request. Read through
     *   [DetectorAcceleratorReport.plan] so "does this config want GPU at all" has
     *   one definition; `preferGpu = false` yields a plan without GPU in it.
     * @param coldStart what the cold-start build actually produced. `null` means it
     *   produced nothing — the asset or the MediaPipe runtime is unusable, and the
     *   GPU delegate would read the same asset through the same runtime, so there is
     *   nothing to gain by spending a thread and 7.5s failing again.
     */
    fun initialStage(preferGpu: Boolean, coldStart: DetectorAccelerator?): GpuUpgradeStage = when {
        DetectorAccelerator.GPU !in DetectorAcceleratorReport.plan(preferGpu) ->
            GpuUpgradeStage.NOT_REQUESTED
        coldStart != DetectorAccelerator.CPU -> GpuUpgradeStage.NOT_REQUESTED
        else -> GpuUpgradeStage.PENDING
    }

    /**
     * Whether the background attempt may run.
     *
     * [GpuUpgradeStage.PENDING] is the only stage that permits one, and [resolve]
     * cannot produce `PENDING`, so an attempt happens at most once per detector. A
     * driver that refuses once refuses in a loop, and each turn of that loop costs
     * 7.5s of a core.
     */
    fun shouldAttempt(stage: GpuUpgradeStage): Boolean = stage == GpuUpgradeStage.PENDING

    /**
     * What one attempt came to.
     *
     * @param created the GPU `ObjectDetector` was constructed without throwing.
     * @param validated a real `detect()` on that detector returned without throwing.
     *   **This, not [created], is what adoption is gated on.**
     */
    fun resolve(created: Boolean, validated: Boolean): GpuUpgradeStage = when {
        !created -> GpuUpgradeStage.CREATE_FAILED
        !validated -> GpuUpgradeStage.VALIDATION_FAILED
        else -> GpuUpgradeStage.ADOPTED
    }
}
