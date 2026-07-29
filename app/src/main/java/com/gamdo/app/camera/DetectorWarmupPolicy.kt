package com.gamdo.app.camera

/**
 * `ComponentCallbacks2` trim levels, restated rather than imported so this file
 * carries no `android.*` dependency and can be decided on the JVM.
 *
 * They are frozen public-API constants — every app ever compiled sees these exact
 * numbers, so restating them costs nothing and buys a test. The ordering is not a
 * simple severity ramp, which is the reason the two predicates below exist at all:
 *
 * ```
 *  5  RUNNING_MODERATE   foreground, system starting to feel it
 * 10  RUNNING_LOW        foreground
 * 15  RUNNING_CRITICAL   foreground, background processes about to be killed
 * 20  UI_HIDDEN          no longer visible          <- everything >= here is "hidden"
 * 40  BACKGROUND         in the LRU list
 * 60  MODERATE           middle of the LRU list
 * 80  COMPLETE           next to be killed
 * ```
 *
 * On API 34+ the three `RUNNING_*` levels are no longer dispatched to foreground
 * apps. That retires [isForegroundCritical] on new devices; [isAppHidden], which
 * is the branch that actually matters here, still fires everywhere.
 */
private const val TRIM_RUNNING_CRITICAL = 15
private const val TRIM_UI_HIDDEN = 20

private fun isAppHidden(level: Int): Boolean = level >= TRIM_UI_HIDDEN

private fun isForegroundCritical(level: Int): Boolean = level == TRIM_RUNNING_CRITICAL

/**
 * What the caller must do about the shared detection stack.
 *
 * The skip reasons are separate values rather than one `SKIP` so the log line at
 * the call site says *why* nothing was preloaded — "no guide on this launch" has
 * two very different explanations and they are not distinguishable afterwards.
 */
enum class WarmupDecision {
    /** Nothing exists. The caller must construct the stack now. */
    BUILD,

    /** A stack already exists — finished or still loading. Reuse it; never build a second. */
    ADOPT,

    /** Speculative warm-up refused: first run, the user is going to onboarding. */
    SKIP_ONBOARDING,

    /** Speculative warm-up refused: no camera permission, so there is no preview to guide. */
    SKIP_NO_CAMERA_PERMISSION,

    /** Tear the stack down. */
    RELEASE,

    /** Nothing to do. */
    NONE,
}

/**
 * Decides **when the detection stack is built, adopted and released** — the whole
 * of the warm-up policy that does not need a device to be right.
 *
 * ## The problem it exists for
 *
 * Measured on SM-G970N (Android 12, debug, cold start, battery 24.2°C so not
 * thermally throttled), after the preview-blocking fix in [AnalysisThreadResource]:
 *
 * ```
 * CameraStartup: guideConfig 9.7ms (main)
 * CameraStartup: detectorBuild face=24 pose=17 object=7933 seg=18 total=7992ms
 * first detection 9.4s after launch
 * ```
 *
 * The 7.9s is one TFLite model load — the `object` stage, not the pose one. It
 * cannot be made shorter from here, so the only lever left is *starting it
 * sooner*: the build used to be submitted when `CameraScreen` first composed,
 * which is downstream of process start, `Application.onCreate`, the permission
 * gate, the onboarding-flag read and navigation. On this trace that window is
 * roughly a second — worth taking, and worth not overstating.
 *
 * ## What is actually decidable
 *
 * Moving the start earlier turns one composable-scoped resource into a
 * process-scoped one, and that raises three questions with no camera in them:
 *
 *  - **when to start** — a first-run user lands on onboarding and may never open
 *    the camera at all; a user who denied camera permission never gets a preview.
 *    Neither should pay ~5MB of native memory for a model they will not use.
 *  - **how a late consumer adopts an in-flight build** — the camera screen usually
 *    opens *while* the load is still running. It must attach to that build, not
 *    start a second one, and it must not wait for it.
 *  - **when to give the memory back** — a process-scoped resource that is never
 *    released is just a leak with a rationale.
 *
 * This class answers all three and holds no detector, no `Context` and no thread.
 * [SceneDetectorWarmup] is the thin Android shell that performs the decisions.
 *
 * ## Concurrency
 *
 * [preload] is called from a background coroutine and [attach] from the main
 * thread, and on a fast return-to-camera they genuinely overlap. Every method is
 * serialized, and [BUILD][WarmupDecision.BUILD] is handed to **exactly one**
 * caller — that guarantee is the reason this is a class with a lock rather than a
 * few `if`s at the call site.
 */
class DetectorWarmupGate {

    private val lock = Any()

    /** Whether a stack exists *or is being built*. In-flight counts: it is why ADOPT is safe. */
    private var instance = false

    /** Camera screens currently mounted. Not a boolean: a screen can be re-created before the old one is forgotten. */
    private var consumers = 0

    /**
     * A speculative warm-up from outside the camera screen — `Application.onCreate`
     * and `Activity.onStart`.
     *
     * Both flags are the *cost* side of the trade. The build is ~5MB of native
     * memory and ~7.6s of one background thread; a user who is about to see the
     * onboarding cards, or who has refused the camera, gets neither.
     */
    fun preload(onboardingComplete: Boolean, cameraPermissionGranted: Boolean): WarmupDecision =
        synchronized(lock) {
            when {
                instance -> WarmupDecision.ADOPT
                !onboardingComplete -> WarmupDecision.SKIP_ONBOARDING
                !cameraPermissionGranted -> WarmupDecision.SKIP_NO_CAMERA_PERMISSION
                else -> {
                    instance = true
                    WarmupDecision.BUILD
                }
            }
        }

    /**
     * The camera screen mounting.
     *
     * Unconditional: the screen only composes behind the permission gate and it
     * cannot draw a guide without the stack, so there is nothing left to weigh.
     * A first-run user reaches the camera through this path and pays the build
     * here — exactly where the cost used to be, so nothing regresses for them.
     */
    fun attach(): WarmupDecision = synchronized(lock) {
        consumers++
        if (instance) {
            WarmupDecision.ADOPT
        } else {
            instance = true
            WarmupDecision.BUILD
        }
    }

    /**
     * The camera screen leaving composition.
     *
     * Deliberately **not** a teardown. Camera → album → back is one tap each way,
     * and releasing on the way out would charge the user the full 7.6s on the way
     * in; that is precisely the cost this class exists to avoid. Memory is given
     * back by [onTrimMemory] instead, which knows something `onDispose` does not:
     * whether the app is still on screen.
     *
     * Clamped at zero so a stray extra detach cannot make the counter negative and
     * strand a real consumer's claim.
     */
    fun detach(): Unit = synchronized(lock) {
        if (consumers > 0) consumers--
    }

    /**
     * `Application.onTrimMemory`.
     *
     * Three rules, in order:
     *
     *  1. **A mounted camera screen keeps it.** The stack is in active use on the
     *     analysis thread; dropping it under the user would stop the guide with
     *     the preview still running, and it would be rebuilt moments later anyway.
     *     Android's contract asks for caches, and an in-use detector is not one.
     *  2. **Not visible and unclaimed → release.** The clearest possible signal
     *     that ~5MB of model is dead weight.
     *  3. **Foreground but critical → release.** The app is still on screen but
     *     the system is about to start killing processes; a warm cache is the
     *     cheapest thing to give up, and the alternative is being killed.
     */
    fun onTrimMemory(level: Int): WarmupDecision = synchronized(lock) {
        when {
            !instance -> WarmupDecision.NONE
            consumers > 0 -> WarmupDecision.NONE
            isAppHidden(level) || isForegroundCritical(level) -> {
                instance = false
                WarmupDecision.RELEASE
            }
            else -> WarmupDecision.NONE
        }
    }

    /**
     * Forgets the current stack so the next [attach] or [preload] builds again.
     *
     * The build-failed path. Before the stack became process-scoped, a failed
     * build was retried the next time the camera screen composed, because the
     * whole thing lived in a `remember`. Without this, one failure would leave the
     * guide dead for the rest of the process — a regression introduced by the
     * warm-up rather than by anything the user did.
     *
     * @return whether there was anything to forget, so the caller knows whether it
     *   also has native state to close.
     */
    fun invalidate(): Boolean = synchronized(lock) {
        val had = instance
        instance = false
        had
    }

    /**
     * Whether a warm-up would have anything to do.
     *
     * Lets `Activity.onStart` skip a disk-backed onboarding-flag read on every
     * single resume when the stack is already warm — the read is only worth paying
     * for when its answer can change something.
     */
    fun needsWarmUp(): Boolean = synchronized(lock) { !instance }

    /** Mounted camera screens. Diagnostics and tests only. */
    fun consumerCount(): Int = synchronized(lock) { consumers }
}
