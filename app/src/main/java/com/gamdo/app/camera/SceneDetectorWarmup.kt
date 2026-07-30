package com.gamdo.app.camera

import android.content.Context
import android.util.Log
import com.gamdo.app.BuildConfig
import com.gamdo.app.detect.EfficientDetSceneDetector
import com.gamdo.app.detect.MlKitFaceDetector
import com.gamdo.app.detect.SceneDetector
import com.gamdo.app.detect.ThrottledFaceDetector
import com.gamdo.app.detect.ThrottledObjectSceneDetector
import com.gamdo.app.guide.GuideConfigBundle
import com.gamdo.app.guide.parseGuideConfigBundle
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Same tag as `CameraScreen`'s startup lines, so one grep still reads the whole launch. */
private const val TAG = "CameraStartup"

/** Separate tag so per-stage timing greps cleanly out of the per-frame chatter. */
private const val STAGE_TAG = "DetectStage"

/** Named so `detectorBuild … (gamdo-analysis)` says which thread, instead of `pool-3-thread-1`. */
private const val ANALYSIS_THREAD_NAME = "gamdo-analysis"

/**
 * The process-scoped analysis thread and the detection stack that runs on it,
 * started **as early as the [DetectorWarmupGate] policy allows** rather than when
 * the camera screen composes.
 *
 * ## Why this exists, and the size of what it can actually buy
 *
 * `object=7933ms` of a `total=7992ms` detector build is one TFLite model load
 * (see [buildSceneDetector]) and **nothing here makes it shorter**. Before this
 * class the build was not even *started* until `CameraScreen` first composed,
 * i.e. after process start, `Application.onCreate`, `PermissionGate`,
 * `GamdoNavHost`'s suspending read of the onboarding flag (a cold Room open), and
 * navigation. That window was time the load could have been using and was not.
 *
 * Be precise about the size of the prize, because it is smaller than "start it
 * earlier" sounds. On the cool-device cold start the first detection arrived 9.4s
 * after launch and the build itself was 8.0s of that, so the recoverable window —
 * launch to first composition — is on the order of **one second**, not eight.
 * Preloading shortens the cold-start wait; it does not remove it. What it *does*
 * remove outright is the repeat: leaving for the album and coming back used to
 * shut the executor down and rebuild from scratch, paying the whole 8s again on a
 * screen the user had already waited for once.
 *
 * The window is no longer a guess, either. `detectorPreload BUILD` is logged when
 * the load is submitted and `detectorLease ADOPT` when the camera screen claims
 * it; the gap between those two lines is exactly the head start, per launch.
 *
 * ## Why the executor is shared, not just the detector
 *
 * [AnalysisThreadResource]'s ordering guarantee is "the build was submitted to
 * this single FIFO executor before any frame task was", which is what makes
 * "detector exists before the first analysed frame" true without a latch or a
 * flag. Preloading onto some *other* thread and handing the result over would
 * throw that away twice: it needs a publication handshake, and it moves a TFLite
 * handle across a thread boundary that its GPU delegate does not promise to
 * survive.
 *
 * So the executor is preloaded too, and the camera screen adopts **the executor
 * and the resource together**. Adoption then needs no synchronization at all: the
 * screen's first frame task is submitted to the same queue the build was
 * submitted to, behind it. A screen that opens mid-build sees `get() == null` and
 * skips those frames, which is the behaviour that already shipped.
 *
 * ## Lifetime
 *
 * Held for the process once built, released by [onTrimMemory] when the app is not
 * visible and no camera screen is mounted. `onDispose` deliberately does not tear
 * it down — see [DetectorWarmupGate.detach].
 *
 * A singleton because there is no process-scoped owner available to hold it:
 * `AppContainer` is the natural home and is another vertical's file. Moving it
 * there later is a field and a constructor argument; nothing in the policy
 * changes, which is why the policy is a separate injectable class and this shell
 * is not.
 */
object SceneDetectorWarmup {

    private val gate = DetectorWarmupGate()

    @Volatile
    private var executor: ExecutorService? = null

    @Volatile
    private var resource: AnalysisThreadResource<SceneDetector>? = null

    @Volatile
    private var config: GuideConfigBundle? = null

    /**
     * What a camera screen holds while it is mounted.
     *
     * The executor and the resource travel together on purpose: using one without
     * the other is what would reintroduce the ordering race.
     */
    class Lease internal constructor(
        val executor: Executor,
        val detector: AnalysisThreadResource<SceneDetector>,
        val guideConfig: GuideConfigBundle,
    )

    /**
     * Starts the load if the policy says the user is going to need it.
     *
     * Call from a background thread — the caller has to read the onboarding flag
     * first, and this method itself does a small asset parse when it wins the race
     * to it. Neither belongs on the main thread during startup.
     *
     * Idempotent and safe to call repeatedly; a second call while the first build
     * is in flight is [WarmupDecision.ADOPT] and does nothing.
     */
    @Synchronized
    fun preload(context: Context, onboardingComplete: Boolean, cameraPermissionGranted: Boolean) {
        val decision = gate.preload(onboardingComplete, cameraPermissionGranted)
        if (BuildConfig.DEBUG) Log.d(TAG, "detectorPreload $decision")
        if (decision == WarmupDecision.BUILD) startBuild(context)
    }

    /**
     * Claims the stack for a mounted camera screen, building it if the preload was
     * skipped or already released. Never blocks on the build.
     *
     * Every caller must pair this with [releaseLease]. `CameraScreen` does it
     * through a `RememberObserver` rather than a `DisposableEffect` so an
     * abandoned composition — a fast navigate-away during first composition —
     * cannot strand the claim and pin the model in memory for the process.
     */
    @Synchronized
    fun lease(context: Context): Lease {
        discardFailedBuild()
        val decision = gate.attach()
        if (BuildConfig.DEBUG) Log.d(TAG, "detectorLease $decision")
        if (decision == WarmupDecision.BUILD) startBuild(context)
        return Lease(
            executor = requireNotNull(executor) { "BUILD must have created the executor" },
            detector = requireNotNull(resource) { "BUILD must have created the resource" },
            guideConfig = guideConfig(context),
        )
    }

    /** The mounted camera screen going away. Does not tear anything down. */
    @Synchronized
    fun releaseLease() {
        gate.detach()
    }

    /** Whether a warm-up would have anything to do — lets `onStart` skip a disk read. */
    @Synchronized
    fun needsWarmUp(): Boolean = gate.needsWarmUp()

    /** `Application.onTrimMemory`. */
    @Synchronized
    fun onTrimMemory(level: Int) {
        if (gate.onTrimMemory(level) == WarmupDecision.RELEASE) {
            Log.i(TAG, "detector released on trim level=$level")
            tearDown()
        }
    }

    /**
     * `assets/guide_config.json`, parsed once per process (CFG-1).
     *
     * Memoized because there are now two readers — this class's build factory and
     * `CameraScreen`'s view-model — and because the preloaded path parses it on
     * the analysis thread, which leaves the composition-thread read free. Without
     * a preload it is parsed on the composition thread exactly as it was before,
     * so the first-run path is unchanged.
     */
    @Synchronized
    fun guideConfig(context: Context): GuideConfigBundle = config ?: parseGuideConfig(context).also {
        config = it
    }

    private fun parseGuideConfig(context: Context): GuideConfigBundle {
        val startNs = System.nanoTime()
        val parsed = runCatching {
            context.applicationContext.assets.open("guide_config.json").bufferedReader()
                .use { reader -> parseGuideConfigBundle(reader.readText()) }
        }.getOrDefault(GuideConfigBundle())
        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "guideConfig %.1fms (%s)".format(
                    (System.nanoTime() - startNs) / 1_000_000.0,
                    Thread.currentThread().name,
                ),
            )
        }
        return parsed
    }

    /**
     * Submits the build. Returns immediately — the factory runs on the executor,
     * which is the entire point of [AnalysisThreadResource].
     *
     * The config is resolved *inside* the factory so that on the preload path the
     * asset parse also lands on the analysis thread instead of on whichever thread
     * happened to call in.
     */
    private fun startBuild(context: Context) {
        val appContext = context.applicationContext
        val exec = executor ?: Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, ANALYSIS_THREAD_NAME)
        }.also { executor = it }
        resource = AnalysisThreadResource(
            executor = exec,
            close = SceneDetector::close,
        ) {
            buildSceneDetector(appContext, guideConfig(appContext))
        }
    }

    /**
     * Drops a stack whose build threw, so the next mount retries.
     *
     * `failure != null && get() == null` is exactly "the build ran and failed":
     * an in-flight build has neither, a finished one has a value. Retrying used to
     * be free — the resource lived in a `remember`, so re-entering the camera
     * rebuilt it — and this keeps that true now that it does not.
     */
    private fun discardFailedBuild() {
        val current = resource ?: return
        val failure = current.failure ?: return
        if (current.get() != null) return
        Log.w(TAG, "previous detector build failed; rebuilding on next lease", failure)
        tearDown()
    }

    /**
     * Releases the detector on the analysis thread and lets the thread finish.
     *
     * `release()` queues the teardown; `shutdown()` (never `shutdownNow()`) lets
     * that queued task run before the thread ends. Both references are dropped so
     * a later [preload] or [lease] starts clean.
     */
    private fun tearDown() {
        gate.invalidate()
        resource?.release()
        executor?.shutdown()
        resource = null
        executor = null
    }
}

/**
 * Builds the detection stack. **Called on the analysis executor, never on the
 * composition thread** — see [AnalysisThreadResource] for the 6.4s of black
 * preview that first moved it off there, and [SceneDetectorWarmup] for why it now
 * also starts before the camera screen exists.
 *
 * The per-stage timings exist because the cost is invisible to logcat timestamps
 * once it is off the main thread: it used to be bracketed by CameraX's own log
 * lines and now is bracketed by nothing.
 *
 * They are what identified the cost, and the answer is lopsided enough to name
 * precisely. `object` is `ms(t2, t3)`, which brackets exactly one thing —
 * [EfficientDetSceneDetector]'s constructor. Cold start on SM-G970N, battery at
 * 24.2°C so not thermally throttled:
 *
 * ```
 * detectorBuild face=24 pose=17 object=7933 seg=18 total=7992ms
 * ```
 *
 * **99.3% of the build was that one construction.** The three ML Kit clients cost
 * 59ms between them. Read the stage names carefully before optimizing anything
 * here: `pose` is ML Kit Pose at 17ms and is not the problem, and "the MediaPipe
 * model" means the object detector, not that one.
 *
 * ## What that 7.9s actually was (2026-07-29)
 *
 * Not the 4.5MB `efficientdet_lite0_coco_int8.tflite` load, which is the obvious
 * suspect and the wrong one. Forcing CPU-only in an isolated worktree, cold process,
 * twice: `object=281ms / 228ms`, `total=376ms / 313ms` — same asset, same cold page
 * cache. **The 7.5s was GPU delegate compilation**, for a delegate that then failed
 * its first inference on this device.
 *
 * `EfficientDetSceneDetector` therefore builds on CPU first and pursues the GPU on
 * a thread of its own, so this bracket should now read `object≈250ms` on every
 * device. The GPU attempt is no longer inside it; grep the `EfficientDet` tag for
 * `gpuUpgrade=` to see where it went.
 */
private fun buildSceneDetector(context: Context, guideConfig: GuideConfigBundle): SceneDetector {
    val t0 = System.nanoTime()
    // Face was the last stage running on every frame: 63.5ms mean of a 178.5ms
    // median frame on a cool device (24.2°C), 34% of it. Same reasoning as pose
    // below — the model does not get cheaper on an empty frame, so cadence is
    // the only lever. See `faceRefreshEveryFrames` for why the divisor is 2.
    val faceDetector = ThrottledFaceDetector(
        MlKitFaceDetector(),
        refreshEveryFrames = guideConfig.objectGuide.faceRefreshEveryFrames,
    )
    val t1 = System.nanoTime()
    // V3.1 removes live pose inference. Person framing uses face + EfficientDet
    // person boxes, so no pose model is initialized during camera warmup.
    val t2 = t1
    // CameraX already keeps only the newest frame. Refreshing objects on
    // every processed frame gives the 3/5 tracker enough real evidence
    // to meet the two-second first-layout target without a queue.
    val objectDetector = ThrottledObjectSceneDetector(
        EfficientDetSceneDetector(context, guideConfig.toEfficientDetConfig()),
        refreshEveryFrames = guideConfig.objectGuide.objectRefreshEveryFrames,
    )
    val t3 = System.nanoTime()
    val t4 = System.nanoTime()

    if (BuildConfig.DEBUG) {
        fun ms(from: Long, to: Long) = (to - from) / 1_000_000.0
        Log.d(
            TAG,
            "detectorBuild face=%.0f pose=0 object=%.0f seg=%.0f total=%.0fms (%s)".format(
                ms(t0, t1), ms(t1, t2), ms(t2, t3), ms(t3, t4), ms(t0, t4),
                Thread.currentThread().name,
            ),
        )
    }

    return SceneDetector(
        faceDetector = faceDetector,
        objectDetector = objectDetector,
        // Subject segmentation is intentionally not wired into the live path.
        // Per-stage cost, debug builds only. Every ML Kit model here blocks the
        // single analysis thread in turn and none gets cheaper on an empty
        // frame, so "which one" is not answerable from the HUD's whole-lambda
        // number.
        stageSink = if (BuildConfig.DEBUG) {
            { timings -> Log.d(STAGE_TAG, timings.format()) }
        } else {
            null
        },
    )
}
