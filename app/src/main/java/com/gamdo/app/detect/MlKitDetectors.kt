package com.gamdo.app.detect

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions

private const val TAG = "MlKitDetectors"

/**
 * ML Kit face detector — fast mode + classification (eye-open probability).
 * Returns normalized boxes. (§2-2)
 */
class MlKitFaceDetector : FaceDetector {

    /**
     * Classification (eye-open / smile) is **off**.
     *
     * It is a separate model pass on every face, and on device this detector was
     * measured at 98.5ms per frame — the single largest cost in the analysis
     * pipeline, running unthrottled at 37% of a 263ms budget.
     *
     * Nothing needs what it produced. `leftEyeOpenProbability` had exactly one
     * production reader, `SceneProposalEngine`'s person-confidence fallback, and
     * that reader was a defect (review_report #17): eyelid state standing in for
     * detection confidence. The fix there removed the last consumer, so the pass
     * was paying for a wrong answer. The fields stay on [FaceObservation] and
     * simply read null; the debug HUD prints `?` for them.
     *
     * Contours are also off and always were — the guide draws a bracket, not a
     * face mesh.
     */
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
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
 * On-device object detection and tracking for non-person subjects. STREAM_MODE
 * supplies tracking IDs across frames; classification is coarse and used only as
 * an internal hint, never as user-facing certainty.
 */
@Deprecated(
    message = "물체 검출은 EfficientDet 하나로 간다(오너 결정 O-6, 2026-07-28). 이 검출기는 " +
        "EfficientDet이 중앙에서 아무것도 못 찾을 때마다 **추가로** 돌면서 프레임을 527ms로 " +
        "만들고 있었다 — 빈 벽·천장처럼 흔한 장면에서 그 조건이 항상 참이라 기기 로그에서 " +
        "분석 39프레임에 39회 호출됐다. 되살리려면 1회당 EfficientDet의 3배 비용을 " +
        "어디서 낼지부터 정해야 한다. `ObjectDetectorWiringTest`가 재배선을 막는다.",
    level = DeprecationLevel.ERROR,
)
class MlKitObjectDetector(
    private val multiScaleConfig: MultiScaleObjectDetectionConfig = MultiScaleObjectDetectionConfig(),
) : ObjectSceneDetector {

    private val detector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            // Scene tracking/stabilization is owned by StableSceneTracker. A
            // single-image detector avoids ML Kit's stream-mode warm-up and
            // tracking state, which was returning an empty list on device.
            .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build(),
    )
    private val multiScaleScheduler = MultiScaleFallbackScheduler(multiScaleConfig)

    override fun detect(frame: AnalysisFrame): List<ObjectObservation> {
        val image = frame.image as? InputImage ?: return emptyList()
        val primary = detectInput(image, frame.width, frame.height)
        val crop = ObjectDetectionCrop.centered(multiScaleConfig.cropScale)
        val cropBitmap = if (
            frame.cropBitmapProvider != null && multiScaleScheduler.shouldRun(primary)
        ) {
            runCatching { frame.cropBitmapProvider.invoke(crop) }
                .onFailure { Log.w(TAG, "multi-scale crop unavailable", it) }
                .getOrNull()
        } else {
            null
        }
        val observations = if (cropBitmap == null) {
            primary
        } else {
            try {
                val cropped = detectInput(InputImage.fromBitmap(cropBitmap, 0), cropBitmap.width, cropBitmap.height)
                MultiScaleObjectDetection.mergeDistinct(
                    primary = primary,
                    secondary = MultiScaleObjectDetection.remapToFrame(cropped, crop),
                    duplicateIou = multiScaleConfig.duplicateIou,
                )
            } finally {
                if (!cropBitmap.isRecycled) cropBitmap.recycle()
            }
        }
        Log.d(
            TAG,
            "objects=${observations.size} primary=${primary.size} crop=${if (cropBitmap != null) "on" else "off"} " +
                observations.joinToString(separator = ";") { objectObservation ->
                    "box=${objectObservation.box.left.formatBox()},${objectObservation.box.top.formatBox()}," +
                        "${objectObservation.box.right.formatBox()},${objectObservation.box.bottom.formatBox()} " +
                        "label=${objectObservation.labels.firstOrNull() ?: "unknown"}"
                },
        )
        return observations
    }

    private fun detectInput(image: InputImage, width: Int, height: Int): List<ObjectObservation> {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val detected = runCatching { Tasks.await(detector.process(image)) }
            .onFailure { Log.w(TAG, "object detect failed", it) }
            .getOrDefault(emptyList())
        return detected.mapNotNull { item ->
                val bounds = item.boundingBox
                val labels = item.labels.map { it.text }.filter { it.isNotBlank() }
                // A missing label is not a 0.5-confidence classification. Keep
                // it as an unclassified box so the scene guide can use a valid
                // segmentation mask without claiming to know the object type.
                val classificationConfidence = item.labels.maxOfOrNull { it.confidence }
                val category = SceneRecognitionPolicy.categoryFor(labels)
                val normalized = NormalizedBox(
                    (bounds.left / w).coerceIn(0f, 1f),
                    (bounds.top / h).coerceIn(0f, 1f),
                    (bounds.right / w).coerceIn(0f, 1f),
                    (bounds.bottom / h).coerceIn(0f, 1f),
                )
                if (!SceneRecognitionPolicy.isValidBox(normalized)) return@mapNotNull null
                ObjectObservation(
                    box = normalized,
                    trackingId = item.trackingId,
                    labels = labels,
                    classificationConfidence = classificationConfidence,
                    category = category,
                )
            }
    }

    override fun close() {
        multiScaleScheduler.reset()
        detector.close()
    }
}

private fun Float.formatBox(): String = "%.2f".format(this)

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

    /**
     * Blocks until segmentation finishes. **Deliberately no timeout.**
     *
     * There used to be one — 180ms, then 1200ms — and it was unsafe rather than
     * merely slow. `InputImage.fromMediaImage` wraps the CameraX `Image` by
     * reference with no copy, and `FrameAnalyzer` closes that `ImageProxy` in a
     * `finally` the moment this call returns. On a timeout the ML Kit task is
     * still running: the buffer goes back to the `ImageReader` queue, gets
     * refilled with a different frame, and the abandoned task reads it. The
     * symptom is a mask from nowhere, or a native read of recycled memory
     * (review_report #14).
     *
     * The reported remedy — `task.cancel()` — does not exist:
     * `com.google.android.gms.tasks.Task` has no `cancel`, and
     * `SubjectSegmenter.process(InputImage)` takes no `CancellationToken`. So the
     * choice was to either extend the ImageProxy's lifetime past the deadline or
     * to stop having a deadline. Blocking is what the other three detectors here
     * already do (`Detectors.kt`: "Implementations block until done"), CameraX is
     * bound `STRATEGY_KEEP_ONLY_LATEST` so a slow frame is dropped rather than
     * queued, and the throttle above means this runs once every N frames anyway.
     *
     * Owner decision, 2026-07-28. The cost is visible in the `DetectStage` log:
     * device-measured at ~570ms per run when warm, ~0ms on cached frames.
     */
    override fun detect(frame: AnalysisFrame): SegmentationObservation? {
        val image = frame.image as? InputImage ?: return null
        return runCatching {
            val result = Tasks.await(segmenter.process(image))
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
