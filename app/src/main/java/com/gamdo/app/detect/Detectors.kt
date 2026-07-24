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

/**
 * Runs the configured face + pose detectors over one frame. B's
 * FrameFeatureCalculator (Day 2) consumes [DetectionResult] downstream.
 */
class SceneDetector(
    private val faceDetector: FaceDetector,
    private val poseDetector: PoseDetector,
) {
    fun detect(frame: AnalysisFrame): DetectionResult =
        DetectionResult(
            faces = faceDetector.detect(frame),
            pose = poseDetector.detect(frame),
        )

    fun close() {
        faceDetector.close()
        poseDetector.close()
    }
}
