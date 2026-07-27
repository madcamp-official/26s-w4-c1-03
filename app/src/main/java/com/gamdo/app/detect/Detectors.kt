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
    fun close()
}

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

    override fun detect(frame: AnalysisFrame): List<ObjectObservation> {
        frameCount++
        if (frameCount == 1 || frameCount % refreshEveryFrames == 0) {
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
    private val guideCandidateStabilizer = GuideCandidateStabilizer()

    fun detect(frame: AnalysisFrame): DetectionResult =
        DetectionResult(
            faces = faceDetector.detect(frame),
            pose = poseDetector.detect(frame),
            objects = customObjectDetector?.detect(frame).orEmpty()
                .ifEmpty { objectDetector?.detect(frame).orEmpty() },
            segmentation = subjectSegmenter?.detect(frame),
        ).let { result ->
            val segmentation = result.segmentation
            val candidate = result.objects
                .maxByOrNull { it.box.width * it.box.height * it.confidence }
                ?.let { candidateObject ->
                    val eligible = SceneRecognitionPolicy.isGuideEligible(
                        category = candidateObject.category,
                        detectionConfidence = candidateObject.confidence,
                        mask = candidateObject.mask ?: segmentation,
                    )
                    guideCandidateStabilizer.accept(
                        candidateObject.copy(
                            mask = candidateObject.mask ?: segmentation,
                            isGuideEligible = eligible,
                        ),
                    )
                }
            val stabilizedObjects: List<ObjectObservation> = if (candidate != null) {
                result.objects.map { sceneObject: ObjectObservation ->
                    if (sceneObject.trackingId == candidate.trackingId &&
                        sceneObject.box == candidate.box
                    ) candidate else sceneObject
                }
            } else {
                result.objects
            }
            result.copy(objects = stabilizedObjects)
        }

    fun close() {
        faceDetector.close()
        poseDetector.close()
        objectDetector?.close()
        subjectSegmenter?.close()
        customObjectDetector?.close()
    }

    fun reset() = guideCandidateStabilizer.reset()
}
