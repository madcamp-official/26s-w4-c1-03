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
    private val refreshEveryFrames: Int = 6,
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
 * Runs the configured face + pose detectors over one frame. B's
 * FrameFeatureCalculator (Day 2) consumes [DetectionResult] downstream.
 */
class SceneDetector(
    private val faceDetector: FaceDetector,
    private val poseDetector: PoseDetector,
    private val objectDetector: ObjectSceneDetector? = null,
    private val subjectSegmenter: SubjectSceneSegmenter? = null,
    private val customObjectDetector: CustomSceneDetector? = null,
) {
    private val stableSceneTracker = StableSceneTracker()

    fun detect(frame: AnalysisFrame): DetectionResult {
        val faces = faceDetector.detect(frame)
        val pose = poseDetector.detect(frame)
        // Subject segmentation is the generic foreground path. ML Kit's
        // classifier intentionally returns no item for many ordinary objects;
        // that must not make the camera behave as if the scene were empty.
        val segmentation = subjectSegmenter?.detect(frame)
        val objectBatch = when {
            customObjectDetector != null -> customObjectDetector.detectBatch(frame)
            objectDetector != null -> objectDetector.detectBatch(frame)
            else -> ObjectDetectionBatch(emptyList(), isFresh = true, sequenceId = 0L)
        }
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
