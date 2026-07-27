package com.gamdo.app.detect

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions

private const val TAG = "MlKitDetectors"

/**
 * ML Kit face detector — fast mode + classification (eye-open probability).
 * Returns normalized boxes. (§2-2)
 */
class MlKitFaceDetector : FaceDetector {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .build(),
    )

    override fun detect(frame: AnalysisFrame): List<FaceObservation> {
        val image = frame.image as? InputImage ?: return emptyList()
        val w = frame.width.toFloat().coerceAtLeast(1f)
        val h = frame.height.toFloat().coerceAtLeast(1f)
        return runCatching { Tasks.await(detector.process(image)) }
            .onFailure { Log.w(TAG, "face detect failed", it) }
            .getOrDefault(emptyList())
            .map { face ->
                val b = face.boundingBox
                FaceObservation(
                    box = NormalizedBox(b.left / w, b.top / h, b.right / w, b.bottom / h),
                    leftEyeOpenProbability = face.leftEyeOpenProbability,
                    rightEyeOpenProbability = face.rightEyeOpenProbability,
                    headEulerAngleZ = face.headEulerAngleZ,
                )
            }
    }

    override fun close() = detector.close()
}

/**
 * ML Kit pose detector — stream mode, 33 landmarks with in-frame likelihood.
 * Returns normalized landmark points. (§2-2)
 */
class MlKitPoseDetector : PoseDetector {

    private val detector = PoseDetection.getClient(
        PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build(),
    )

    override fun detect(frame: AnalysisFrame): PoseObservation? {
        val image = frame.image as? InputImage ?: return null
        val w = frame.width.toFloat().coerceAtLeast(1f)
        val h = frame.height.toFloat().coerceAtLeast(1f)
        val pose = runCatching { Tasks.await(detector.process(image)) }
            .onFailure { Log.w(TAG, "pose detect failed", it) }
            .getOrNull() ?: return null

        val marks = pose.allPoseLandmarks
        if (marks.isEmpty()) return null
        val points = marks.map { m ->
            PoseLandmarkPoint(
                type = m.landmarkType,
                x = m.position.x / w,
                y = m.position.y / h,
                inFrameLikelihood = m.inFrameLikelihood,
            )
        }
        return PoseObservation(
            landmarks = points,
            averageInFrameLikelihood = points.map { it.inFrameLikelihood }.average().toFloat(),
        )
    }

    override fun close() = detector.close()
}

/**
 * On-device object detection and tracking for non-person subjects. STREAM_MODE
 * supplies tracking IDs across frames; classification is coarse and used only as
 * an internal hint, never as user-facing certainty.
 */
class MlKitObjectDetector : ObjectSceneDetector {

    private val detector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build(),
    )

    override fun detect(frame: AnalysisFrame): List<ObjectObservation> {
        val image = frame.image as? InputImage ?: return emptyList()
        val w = frame.width.toFloat().coerceAtLeast(1f)
        val h = frame.height.toFloat().coerceAtLeast(1f)
        return runCatching { Tasks.await(detector.process(image)) }
            .onFailure { Log.w(TAG, "object detect failed", it) }
            .getOrDefault(emptyList())
            .mapNotNull { item ->
                val bounds = item.boundingBox
                val labels = item.labels.map { it.text }.filter { it.isNotBlank() }
                val confidence = item.labels.maxOfOrNull { it.confidence } ?: 0.5f
                ObjectObservation(
                    box = NormalizedBox(
                        bounds.left / w,
                        bounds.top / h,
                        bounds.right / w,
                        bounds.bottom / h,
                    ),
                    confidence = confidence.coerceIn(0f, 1f),
                    trackingId = item.trackingId,
                    labels = labels,
                )
            }
    }

    override fun close() = detector.close()
}
