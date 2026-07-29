package com.gamdo.app.detect

/**
 * Detection interfaces (§2-2). Kept behind interfaces so real ML Kit detectors
 * can be swapped for fakes in tests. Implementations block until done (safe on
 * the dedicated analysis thread with KEEP_ONLY_LATEST backpressure).
 */
interface FaceDetector {
    fun detect(frame: AnalysisFrame): List<FaceObservation>
    fun close()
}

interface PoseDetector {
    fun detect(frame: AnalysisFrame): PoseObservation?
    fun close()
}

interface ObjectSceneDetector {
    fun detect(frame: AnalysisFrame): List<ObjectObservation>
    fun detectBatch(frame: AnalysisFrame): ObjectDetectionBatch =
        ObjectDetectionBatch(detect(frame), isFresh = true, sequenceId = 0L)
    fun close()
}

/** Allows the camera session to stop expensive object inference after fixation. */
interface PausableObjectSceneDetector {
    fun setObjectDetectionPaused(paused: Boolean)
}

data class ObjectDetectionBatch(
    val objects: List<ObjectObservation>,
    val isFresh: Boolean,
    val sequenceId: Long,
)

interface SubjectSceneSegmenter {
    fun detect(frame: AnalysisFrame): SegmentationObservation?
    fun close()
}

/**
 * Keeps the heavier object detector off the critical path on every frame. Face
 * and pose can continue at the camera analysis cadence while objects are refreshed
 * every [refreshEveryFrames] calls and tracked results are reused in between.
 */
class ThrottledObjectSceneDetector(
    private val delegate: ObjectSceneDetector,
    private val refreshEveryFrames: Int = 3,
) : ObjectSceneDetector, AcceleratorReporting, PausableObjectSceneDetector {
    init {
        require(refreshEveryFrames >= 1)
    }

    /**
     * Pass-through. The throttle picks a cadence, not a processor, so it has
     * nothing of its own to report — but it is what the host holds, so without
     * this forward the accelerator record would be unreachable from outside.
     */
    override val acceleratorReport: DetectorAcceleratorReport?
        get() = (delegate as? AcceleratorReporting)?.acceleratorReport

    private var frameCount = 0
    private var lastResult: List<ObjectObservation> = emptyList()
    private var sequenceId = 0L
    @Volatile private var paused = false

    override fun setObjectDetectionPaused(paused: Boolean) {
        this.paused = paused
        if (!paused) frameCount = 0
    }

    override fun detect(frame: AnalysisFrame): List<ObjectObservation> {
        return detectBatch(frame).objects
    }

    override fun detectBatch(frame: AnalysisFrame): ObjectDetectionBatch {
        if (paused) return ObjectDetectionBatch(lastResult, isFresh = false, sequenceId = sequenceId)
        frameCount++
        if (frameCount == 1 || frameCount % refreshEveryFrames == 0) {
            lastResult = delegate.detect(frame)
            sequenceId++
            return ObjectDetectionBatch(lastResult, isFresh = true, sequenceId = sequenceId)
        }
        return ObjectDetectionBatch(lastResult, isFresh = false, sequenceId = sequenceId)
    }

    override fun close() = delegate.close()

    fun reset() {
        frameCount = 0
        lastResult = emptyList()
        sequenceId = 0L
    }
}

/**
 * Halves how often the face model runs.
 *
 * Face was the **last stage in the pipeline with no throttle at all**. Measured on
 * SM-G970N over 80 frames at AP ~50°C: face 103.0ms mean / 103.4ms median on every
 * frame, against a total of 405.4 mean / 302.6 median — 34% of the median frame,
 * for 3.31 fps overall. Pose was already at 1/2, segmentation at 1/12, and objects
 * are deliberately 1/1. Like every model here it does not get cheaper on an empty
 * frame — it scans for a face and reports none — so cadence is the only lever the
 * app has. It is a particularly pure one in this case: the repeating
 * `Replacing 65 out of 65 node(s)` TFLite pair, twice per frame forever, is ML
 * Kit's own face detector rebuilding an interpreter per call, and the only way to
 * pay for that less often is to call it less often.
 *
 * See `faceRefreshEveryFrames` in `ObjectGuideConfigJson` for why the divisor is 2
 * and not 3 or 4; the value is an asset key, and this default is only the fallback
 * for a missing or corrupt asset.
 *
 * **An empty list clears the cache.** This is the list-shaped version of the
 * property [ThrottledPoseDetector] documents for `null`, and the reason
 * `review_report` #15 is not reintroduced here. For a nullable result the trap is
 * `?.let { lastResult = it }`; for a list it is `takeIf { it.isNotEmpty() }` — the
 * same bug wearing a collection's clothes. An empty list is a *result*, not the
 * absence of one: it says "no face in this frame", which is exactly what a person
 * walking out of frame produces. Treating it as "no new information" would keep
 * `SceneGuideSessionController` feeding a PERSON candidate into the 3/5 tracker
 * after the person had gone, and keep `brightnessSample`'s face region pinned to
 * empty wall. So the refresh frame assigns the delegate's list unconditionally,
 * and between refreshes the last *decision* is reused, including a decision of
 * "nobody there".
 *
 * A list can also go stale in a way a nullable cannot — in its **count**. Two
 * faces down to one is a non-empty refresh, so an emptiness check would miss it,
 * while whole-value replacement handles both. That matters because every consumer
 * picks the *largest* face ([SceneDetector]'s callers, `SceneProposalEngine`), so
 * a retained second entry can win the selection outright.
 *
 * The cost of the throttle is one frame of delay before an arriving or departing
 * face is noticed. Note it does not *create* the empty-list risk it inherits:
 * `MlKitFaceDetector` already returns `emptyList()` on a failed `Tasks.await`, so
 * a transient ML Kit error has always been able to blank the face for a frame.
 * This makes such a blank last up to one frame longer, which is well inside
 * `OverlayStabilizer`'s `visibleHoldFrames`/`silhouetteHoldFrames` of 6.
 */
class ThrottledFaceDetector(
    private val delegate: FaceDetector,
    private val refreshEveryFrames: Int = 2,
) : FaceDetector {
    init {
        require(refreshEveryFrames >= 1) { "refreshEveryFrames must be >= 1, was $refreshEveryFrames" }
    }

    private var frameCount = 0
    private var lastResult: List<FaceObservation> = emptyList()

    override fun detect(frame: AnalysisFrame): List<FaceObservation> {
        frameCount++
        // The explicit first-frame case matters: without it the very first analysed
        // frame of every camera open has no face at all, so the bracket and the
        // face-region brightness sample both arrive late.
        if (frameCount == 1 || frameCount % refreshEveryFrames == 0) {
            // Assign unconditionally — see the class KDoc. Never
            // `takeIf { it.isNotEmpty() }`.
            lastResult = delegate.detect(frame)
        }
        return lastResult
    }

    override fun close() = delegate.close()

    fun reset() {
        frameCount = 0
        lastResult = emptyList()
    }
}

/**
 * Halves how often the pose model runs.
 *
 * Measured on SM-G970N: pose cost **89.8ms of a 263ms frame** and ran on every
 * analysed frame. Like every other model here it does not get cheaper when the
 * frame is empty — it scans for a person and then reports none — so the only
 * lever is cadence (owner decision, 2026-07-28). `OverlayStabilizer` already
 * smooths the guide between updates, so the silhouette and foot marker do not
 * visibly step at half rate.
 *
 * See `poseRefreshEveryFrames` in `ObjectGuideConfigJson` for why the divisor is 2;
 * the value is an asset key, and this default is only the fallback for a missing or
 * corrupt asset.
 *
 * **A null result clears the cache.** This is the deliberate difference from
 * [ThrottledSubjectSceneSegmenter], which writes `?.let { lastResult = it }` and
 * therefore never forgets — once a subject has been seen it keeps being reported
 * after they leave (review_report #15). A pose cache behaving that way would hold
 * a person silhouette over an empty wall. Between refreshes the *last decision* is
 * reused, including a decision of "nobody there".
 *
 * ## [phaseOffset] — why pose runs on the frames face does not
 *
 * [ThrottledFaceDetector] is also at 1/2 and counts from the same starting point,
 * so with no offset the two refresh on exactly the same frames and their costs
 * land together. Measured on SM-G970N once the face throttle was wired, that split
 * the frame time in two: 376ms median when both ran, 118ms when neither did.
 *
 * Staggering them does not change throughput — the same two models run in the same
 * two frames either way, and the FPS number does not move. It changes the worst
 * case. Under `STRATEGY_KEEP_ONLY_LATEST` a 376ms frame is 376ms of dropped
 * frames, so the guide advances in a lurch and then waits; two ~230ms frames
 * advance it evenly. Face is the reference at offset 0 and pose is wired at 1;
 * `DetectorPhaseInterleaveTest` pins the resulting property, which is that after
 * frame 1 no frame runs both. Frame 1 is the exception both classes' first-frame
 * cases create on purpose, and one heavy frame at camera open buys a guide that
 * is not missing its silhouette.
 */
class ThrottledPoseDetector(
    private val delegate: PoseDetector,
    private val refreshEveryFrames: Int = 2,
    private val phaseOffset: Int = 0,
) : PoseDetector {
    init {
        require(refreshEveryFrames >= 1) { "refreshEveryFrames must be >= 1, was $refreshEveryFrames" }
        require(phaseOffset >= 0) { "phaseOffset must be >= 0, was $phaseOffset" }
    }

    private var frameCount = 0
    private var lastResult: PoseObservation? = null

    override fun detect(frame: AnalysisFrame): PoseObservation? {
        frameCount++
        // The explicit first-frame case matters: without it the guide has no pose
        // at all until frame N, which is visible as a late-arriving silhouette on
        // every camera open. It is also the one frame where pose and face
        // deliberately run together — see [phaseOffset].
        if (frameCount == 1 || (frameCount + phaseOffset) % refreshEveryFrames == 0) {
            lastResult = delegate.detect(frame)
        }
        return lastResult
    }

    override fun close() = delegate.close()

    fun reset() {
        frameCount = 0
        lastResult = null
    }
}

class ThrottledSubjectSceneSegmenter(
    private val delegate: SubjectSceneSegmenter,
    // Segmentation is the expensive generic-object fallback. Refresh it less
    // often than face/object detection and reuse the last mask between runs.
    private val refreshEveryFrames: Int = 12,
) : SubjectSceneSegmenter {
    init {
        require(refreshEveryFrames >= 1)
    }

    private var frameCount = 0
    private var lastResult: SegmentationObservation? = null

    override fun detect(frame: AnalysisFrame): SegmentationObservation? {
        frameCount++
        if (frameCount == 1 || frameCount % refreshEveryFrames == 0) {
            // Assign unconditionally. This used to be `?.let { lastResult = it }`,
            // so a null never cleared the cache and the last mask survived for the
            // rest of the session (review_report #15).
            //
            // Null is not an exotic case here. `SegmentationMaskReducer` returns it
            // when the foreground covers too few cells — which is literally the
            // subject leaving the frame — and `subjectBox` in the proposal engine
            // prefers `segmented?.bounds` over live detection, so a stale mask
            // outranks the truth. Panning from a person to a blank wall kept
            // drawing an outline over nothing and kept reporting a confident
            // subject.
            //
            // Between refreshes the last *decision* is still reused, including a
            // decision of "nothing there" — that is the throttle working, not the
            // cache going stale.
            lastResult = delegate.detect(frame)
        }
        return lastResult
    }

    override fun close() = delegate.close()

    fun reset() {
        frameCount = 0
        lastResult = null
    }
}

/**
 * Wall time of each stage inside one [SceneDetector.detect] call, in milliseconds.
 *
 * Exists because the numbers the app already reported could not answer "where do
 * 300ms per frame go?". `FrameAnalyzer`'s `processMs` is the whole lambda, and the
 * only *stage* timed anywhere was `FrameFeatureCalculator` — a pure-Kotlin function
 * costing ~0.1ms that reports comfortably inside its 30ms budget while the frame
 * takes 300. A green budget line on the wrong stage is worse than no line.
 *
 * [segRefreshed] and [objectsFresh] are the load-bearing fields. On a throttled
 * stage's cached frames the model does not run and the timing is microseconds, so
 * averaging refresh and cache frames together hides the real cost by an order of
 * magnitude; a reader must be able to separate them.
 *
 * The cadences are asset values, not constants — `objectRefreshEveryFrames`,
 * `segmentationRefreshEveryFrames` and `faceRefreshEveryFrames` in
 * `guide_config.json`. This KDoc used to name them ("every 12th … every 3rd") and
 * went stale the moment objects moved to config and became 1/1. Read the asset.
 *
 * [faceMs] and [poseMs] have no `fresh` companion. That is a gap rather than a
 * statement that they always run: both are throttled now, so their means mix
 * refresh and cache frames exactly the way this doc warns about. It is left alone
 * because adding fields means touching the host's log format, which is not this
 * file's to change; a reader comparing face means across builds should divide by
 * the asset divisor until then.
 */
data class DetectStageTimings(
    val faceMs: Double,
    val poseMs: Double,
    val segMs: Double,
    val objectMs: Double,
    val postMs: Double,
    val totalMs: Double,
    /** True when segmentation actually ran its model rather than returning cache. */
    val segRefreshed: Boolean,
    /** True when object detection actually ran its model this frame. */
    val objectsFresh: Boolean,
    val segNonNull: Boolean,
) {
    fun format(): String =
        "stage face=%.1f pose=%.1f seg=%.1f obj=%.1f post=%.1f total=%.1f segRun=%s objRun=%s segNonNull=%s"
            .format(faceMs, poseMs, segMs, objectMs, postMs, totalMs, segRefreshed, objectsFresh, segNonNull)
}

/**
 * Runs the configured face + pose detectors over one frame. B's
 * FrameFeatureCalculator (Day 2) consumes [DetectionResult] downstream.
 *
 * @param stageSink optional per-frame timing sink. A sink rather than a `Log` call
 *   so this file stays free of `android.*` and JVM-testable; the host wires it to
 *   logcat behind `BuildConfig.DEBUG`.
 */
class SceneDetector(
    private val faceDetector: FaceDetector,
    private val poseDetector: PoseDetector,
    private val objectDetector: ObjectSceneDetector? = null,
    private val subjectSegmenter: SubjectSceneSegmenter? = null,
    private val customObjectDetector: CustomSceneDetector? = null,
    private val stageSink: ((DetectStageTimings) -> Unit)? = null,
) {
    fun setObjectDetectionPaused(paused: Boolean) {
        (customObjectDetector as? PausableObjectSceneDetector)?.setObjectDetectionPaused(paused)
        (objectDetector as? PausableObjectSceneDetector)?.setObjectDetectionPaused(paused)
    }
    /**
     * Which processor the object stage is actually running on, or `null` when no
     * wired detector can say.
     *
     * The object stage is the heaviest per-frame cost in this class, and whether
     * it is on GPU or CPU changes that cost by an order of magnitude. That fact
     * was previously only inferable from a third-party TFLite log line about node
     * counts; [DetectStageTimings] reports how long the stage took but never what
     * ran it. A debug HUD reading `objectMs` without this is reading a number
     * whose scale it cannot explain.
     */
    val objectAcceleratorReport: DetectorAcceleratorReport?
        get() = (customObjectDetector as? AcceleratorReporting)?.acceleratorReport
            ?: (objectDetector as? AcceleratorReporting)?.acceleratorReport

    fun detect(frame: AnalysisFrame): DetectionResult {
        val t0 = System.nanoTime()
        val faces = faceDetector.detect(frame)
        val t1 = System.nanoTime()
        val pose = poseDetector.detect(frame)
        val t2 = System.nanoTime()
        // Segmentation refines an already-detected foreground outline. It must
        // never invent a single generic object when object detection is empty:
        // a merged foreground mask would turn two adjacent drinks into a false
        // one-slot scene.
        val segmentation = subjectSegmenter?.detect(frame)
        val t3 = System.nanoTime()
        val objectBatch = when {
            customObjectDetector != null -> customObjectDetector.detectBatch(frame)
            objectDetector != null -> objectDetector.detectBatch(frame)
            else -> ObjectDetectionBatch(emptyList(), isFresh = true, sequenceId = 0L)
        }
        val t4 = System.nanoTime()

        stageSink?.let { sink ->
            fun ms(from: Long, to: Long) = (to - from) / 1_000_000.0
            sink(
                DetectStageTimings(
                    faceMs = ms(t0, t1),
                    poseMs = ms(t1, t2),
                    segMs = ms(t2, t3),
                    objectMs = ms(t3, t4),
                    // Upstream removed the phantom-object synthesis and the
                    // StableSceneTracker pass that used to sit here, so there is
                    // no post-stage left to measure. Kept at 0 rather than dropped
                    // from the record: a reader comparing today's numbers with the
                    // 2026-07-28 baseline needs to see that the column went to
                    // zero rather than wonder where it went.
                    postMs = 0.0,
                    totalMs = ms(t0, t4),
                    segRefreshed = ms(t2, t3) > 1.0,
                    objectsFresh = objectBatch.isFresh,
                    segNonNull = segmentation != null,
                ),
            )
        }

        return DetectionResult(
            faces = faces,
            pose = pose,
            objects = objectBatch.objects,
            segmentation = segmentation,
            objectsFresh = objectBatch.isFresh,
            objectSequenceId = objectBatch.sequenceId,
        )
    }

    fun close() {
        faceDetector.close()
        poseDetector.close()
        objectDetector?.close()
        subjectSegmenter?.close()
        customObjectDetector?.close()
    }

    fun reset() = Unit
}
