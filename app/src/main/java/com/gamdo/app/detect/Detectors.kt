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
            delegate.detect(frame)?.let { lastResult = it }
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
    private val stableSceneTracker = StableSceneTracker()

    fun detect(frame: AnalysisFrame): DetectionResult {
        val t0 = System.nanoTime()
        val faces = faceDetector.detect(frame)
        val t1 = System.nanoTime()
        val pose = poseDetector.detect(frame)
        val t2 = System.nanoTime()
        // Subject segmentation is the generic foreground path. ML Kit's
        // classifier intentionally returns no item for many ordinary objects;
        // that must not make the camera behave as if the scene were empty.
        val segmentation = subjectSegmenter?.detect(frame)
        val t3 = System.nanoTime()
        val objectBatch = when {
            customObjectDetector != null -> customObjectDetector.detectBatch(frame)
            objectDetector != null -> objectDetector.detectBatch(frame)
            else -> ObjectDetectionBatch(emptyList(), isFresh = true, sequenceId = 0L)
        }
        val t4 = System.nanoTime()
        val genericBatch = if (objectBatch.objects.isEmpty() && segmentation != null) {
            objectBatch.copy(
                objects = listOf(
                    ObjectObservation(
                        box = segmentation.bounds,
                        detectionConfidence = segmentation.confidence,
                        labels = emptyList(),
                        classificationConfidence = null,
                        category = GuideObjectCategory.UNKNOWN,
                        mask = segmentation,
                    ),
                ),
            )
        } else {
            objectBatch
        }
        val stableObjects = stableSceneTracker.accept(genericBatch)
        val t5 = System.nanoTime()

        stageSink?.let { sink ->
            fun ms(from: Long, to: Long) = (to - from) / 1_000_000.0
            sink(
                DetectStageTimings(
                    faceMs = ms(t0, t1),
                    poseMs = ms(t1, t2),
                    segMs = ms(t2, t3),
                    objectMs = ms(t3, t4),
                    postMs = ms(t4, t5),
                    totalMs = ms(t0, t5),
                    // A cached return costs microseconds; the model run costs tens
                    // to hundreds of ms. 1ms separates them by two orders of
                    // magnitude, so no plumbing into the throttle wrapper is needed.
                    segRefreshed = ms(t2, t3) > 1.0,
                    objectsFresh = objectBatch.isFresh,
                    segNonNull = segmentation != null,
                ),
            )
        }

        return DetectionResult(
            faces = faces,
            pose = pose,
            objects = stableObjects,
            segmentation = segmentation,
            objectsFresh = genericBatch.isFresh,
            objectSequenceId = genericBatch.sequenceId,
        )
    }

    fun close() {
        faceDetector.close()
        poseDetector.close()
        objectDetector?.close()
        subjectSegmenter?.close()
        customObjectDetector?.close()
    }

    fun reset() = stableSceneTracker.reset()
}
