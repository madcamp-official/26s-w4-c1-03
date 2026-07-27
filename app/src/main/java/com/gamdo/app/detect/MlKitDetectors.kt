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
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.util.concurrent.TimeUnit

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
                // A missing label is not a 0.5-confidence classification. Keep
                // it as an unclassified box so the scene guide can use a valid
                // segmentation mask without claiming to know the object type.
                val classificationConfidence = item.labels.maxOfOrNull { it.confidence }
                val confidence = classificationConfidence ?: 0f
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
                    classificationConfidence = classificationConfidence,
                )
            }
    }

    override fun close() = detector.close()
}

/**
 * ML Kit subject segmentation for people, pets, and general foreground objects.
 * The model is unbundled; a failed or still-downloading model returns null so
 * the existing object detector remains the safe guide fallback.
 */
class MlKitSubjectSegmenter : SubjectSceneSegmenter {

    private val reducer = SegmentationMaskReducer()
    private val segmenter = SubjectSegmentation.getClient(
        SubjectSegmenterOptions.Builder()
            .enableForegroundConfidenceMask()
            .build(),
    )

    override fun detect(frame: AnalysisFrame): SegmentationObservation? {
        val image = frame.image as? InputImage ?: return null
        return runCatching {
            val result = Tasks.await(segmenter.process(image), 180, TimeUnit.MILLISECONDS)
            val maskBuffer = result.foregroundConfidenceMask ?: return@runCatching null
            val mask = FloatArray(maskBuffer.remaining()).also { values ->
                maskBuffer.rewind()
                maskBuffer.get(values)
            }
            reducer.reduce(mask, frame.width, frame.height)
        }
            .onFailure { Log.w(TAG, "subject segmentation unavailable", it) }
            .getOrNull()
    }

    override fun close() = segmenter.close()
}
