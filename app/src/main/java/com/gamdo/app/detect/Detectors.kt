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
) : ObjectSceneDetector {
    init {
        require(refreshEveryFrames >= 1)
    }

    private var frameCount = 0
    private var lastResult: List<ObjectObservation> = emptyList()
    private var sequenceId = 0L

    override fun detect(frame: AnalysisFrame): List<ObjectObservation> {
        return detectBatch(frame).objects
    }

    override fun detectBatch(frame: AnalysisFrame): ObjectDetectionBatch {
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
 * Halves how often the pose model runs.
 *
 * Measured on SM-G970N: pose cost **89.8ms of a 263ms frame** and ran on every
 * analysed frame. Like every other model here it does not get cheaper when the
 * frame is empty — it scans for a person and then reports none — so the only
 * lever is cadence (owner decision, 2026-07-28). `OverlayStabilizer` already
 * smooths the guide between updates, so the silhouette and foot marker do not
 * visibly step at half rate.
 *
 * **A null result clears the cache.** This is the deliberate difference from
 * [ThrottledSubjectSceneSegmenter], which writes `?.let { lastResult = it }` and
 * therefore never forgets — once a subject has been seen it keeps being reported
 * after they leave (review_report #15). A pose cache behaving that way would hold
 * a person silhouette over an empty wall. Between refreshes the *last decision* is
 * reused, including a decision of "nobody there".
 */
class ThrottledPoseDetector(
    private val delegate: PoseDetector,
    private val refreshEveryFrames: Int = 2,
) : PoseDetector {
    init {
        require(refreshEveryFrames >= 1) { "refreshEveryFrames must be >= 1, was $refreshEveryFrames" }
    }

    private var frameCount = 0
    private var lastResult: PoseObservation? = null

    override fun detect(frame: AnalysisFrame): PoseObservation? {
        frameCount++
        // The explicit first-frame case matters: without it the guide has no pose
        // at all until frame N, which is visible as a late-arriving silhouette on
        // every camera open.
        if (frameCount == 1 || frameCount % refreshEveryFrames == 0) {
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
 * [segRefreshed] and [objectsFresh] are the load-bearing fields. Segmentation runs
 * every 12th frame and object detection every 3rd; on the other frames both return
 * a cached value in microseconds. Averaging refresh and cache frames together
 * hides the real cost by an order of magnitude, so a reader must be able to
 * separate them.
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
