package com.gamdo.app.detect

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector

private const val TAG = "EfficientDet"

/** Runtime knobs for the bundled mobile object detector. */
data class EfficientDetSceneDetectorConfig(
    val enabled: Boolean = true,
    val modelAsset: String = "models/efficientdet_lite0_coco_int8.tflite",
    val minimumConfidence: Float = 0.25f,
    val maxResults: Int = 8,
    val preferGpu: Boolean = true,
    val centerCropEveryFrames: Int = 4,
    val centerCropScale: Float = 1.60f,
    val fallback: MultiScaleObjectDetectionConfig = MultiScaleObjectDetectionConfig(),
) {
    init {
        require(minimumConfidence in 0f..1f)
        require(maxResults in 1..25)
        require(centerCropEveryFrames >= 1)
        require(centerCropScale in 1.10f..2.0f)
    }
}

/**
 * Bundled EfficientDet-Lite object detector behind the existing P2 seam.
 *
 * The detector accepts an upright bitmap supplied by the CameraX analysis
 * adapter. It deliberately remains synchronous because SceneDetector already
 * runs on CameraX's dedicated KEEP_ONLY_LATEST analysis thread; this prevents
 * stale callbacks from being promoted into the scene window.
 */
class EfficientDetSceneDetector(
    context: Context,
    private val config: EfficientDetSceneDetectorConfig = EfficientDetSceneDetectorConfig(),
) : CustomSceneDetector {
    private data class DetectorHandle(
        val detector: ObjectDetector,
        val delegate: Delegate,
    )

    override val modelId: String = "efficientdet-lite0-coco-int8"

    private val appContext = context.applicationContext

    // B 모듈 리드 승인 수정(오너 결정 O-6, 2026-07-28): ML Kit 폴백 제거.
    //
    // 이 클래스는 EfficientDet이 아무것도 찾지 못하거나 찾은 것이 화면 중앙에
    // 없을 때 MlKitObjectDetector를 **추가로** 돌렸다. 빈 벽·천장·책상처럼
    // 중앙에 물체가 없는 장면에서는 그 조건이 항상 참이라, 두 검출기가 매 프레임
    // 모두 실행됐다. 기기 로그로 확정: 분석 프레임 39개에 ML Kit 호출도 39회.
    //
    // 대가는 프레임당 431.9ms(객체 단계)와 527ms(전체) — 가이드가 초당 1.9회만
    // 갱신됐다. 게다가 폴백 호출마다 cropBitmapProvider가 YUV→RGB 변환·회전·복사로
    // 3.5MB를 새로 만든다.
    //
    // EfficientDet은 1회당 ML Kit의 약 3배 빠르다. 문제는 느린 검출기를 **교체한**
    // 것이 아니라 **조건부로 덧붙인** 것이었다.
    private var detector: DetectorHandle? = if (config.enabled) {
        runCatching { createDetector(appContext) }
            .onFailure { Log.w(TAG, "EfficientDet unavailable — object detection is off this session", it) }
            .getOrNull()
    } else {
        null
    }
    private var frameCount = 0
    private var sequenceId = 0L

    override fun detectBatch(frame: AnalysisFrame): ObjectDetectionBatch {
        // Every "cannot run" path now reports **no objects** rather than reaching for
        // a second detector. An empty batch is the honest answer, and the guide
        // degrades to the person/preset path rather than to a 527ms frame.
        if (detector == null) return empty()
        frameCount++
        sequenceId++
        val provider = frame.cropBitmapProvider ?: return empty()
        val full = runCatching {
            provider(ObjectDetectionCrop(0f, 0f, 1f, 1f))
        }.getOrNull() ?: return empty()

        val primary = try {
            detectBitmap(full)
        } finally {
            if (!full.isRecycled) full.recycle()
        }

        val merged = if (shouldRunCenterCrop(primary)) {
            val crop = ObjectDetectionCrop.centered(config.centerCropScale)
            val cropped = runCatching { provider(crop) }.getOrNull()
            if (cropped == null) {
                primary
            } else {
                try {
                    MultiScaleObjectDetection.mergeDistinct(
                        primary = primary,
                        secondary = detectBitmap(cropped).map { it.remapFrom(crop) },
                        duplicateIou = config.fallback.duplicateIou,
                    )
                } finally {
                    if (!cropped.isRecycled) cropped.recycle()
                }
            }
        } else {
            primary
        }

        return ObjectDetectionBatch(merged, isFresh = true, sequenceId = sequenceId)
    }

    private fun empty() = ObjectDetectionBatch(emptyList(), isFresh = true, sequenceId = sequenceId)

    override fun detect(frame: AnalysisFrame): List<ObjectObservation> = detectBatch(frame).objects

    override fun close() {
        detector?.detector?.close()
    }

    private fun createDetector(context: Context): DetectorHandle {
        val delegates = if (config.preferGpu) listOf(Delegate.GPU, Delegate.CPU) else listOf(Delegate.CPU)
        var lastFailure: Throwable? = null
        delegates.forEach { delegate ->
            runCatching {
                return createDetector(context, delegate)
            }.onFailure { lastFailure = it }
        }
        throw IllegalStateException("Unable to initialize EfficientDet detector", lastFailure)
    }

    private fun createDetector(context: Context, delegate: Delegate): DetectorHandle {
        val base = BaseOptions.builder()
            .setModelAssetPath(config.modelAsset)
            .setDelegate(delegate)
            .build()
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.IMAGE)
            .setMaxResults(config.maxResults)
            .setScoreThreshold(config.minimumConfidence)
            .build()
        return DetectorHandle(ObjectDetector.createFromOptions(context, options), delegate)
    }

    private fun shouldRunCenterCrop(primary: List<ObjectObservation>): Boolean {
        if (frameCount % config.centerCropEveryFrames != 0) return false
        val central = primary.count { candidate ->
            val dx = candidate.box.centerX - 0.5f
            val dy = candidate.box.centerY - 0.5f
            dx * dx + dy * dy <= 0.36f * 0.36f
        }
        return central < 3
    }

    /**
     * Some Samsung GPU drivers can initialise the delegate successfully and
     * still fail on a later GL buffer map. Keep that native failure inside the
     * detector seam: retry once on CPU, then report no objects for this frame
     * rather than crashing the CameraX analyzer thread.
     */
    private fun detectBitmap(bitmap: Bitmap): List<ObjectObservation> {
        val current = detector ?: return emptyList()
        val first = runCatching { detectBitmap(current.detector, bitmap) }
        if (first.isSuccess) return first.getOrThrow()

        if (current.delegate == Delegate.GPU) {
            runCatching { current.detector.close() }
            detector = runCatching { createDetector(appContext, Delegate.CPU) }.getOrNull()
            detector?.let { cpu ->
                return runCatching { detectBitmap(cpu.detector, bitmap) }.getOrDefault(emptyList())
            }
        }
        return emptyList()
    }

    private fun detectBitmap(model: ObjectDetector, bitmap: Bitmap): List<ObjectObservation> {
        val result = model.detect(BitmapImageBuilder(bitmap).build())
        return result.detections().mapNotNull { detection ->
            val category = detection.categories().maxByOrNull { it.score() }
            val bounds = detection.boundingBox()
            val width = bitmap.width.toFloat().coerceAtLeast(1f)
            val height = bitmap.height.toFloat().coerceAtLeast(1f)
            val label = category?.categoryName()?.takeIf { it.isNotBlank() }
            val score = category?.score() ?: 0f
            val normalized = NormalizedBox(
                (bounds.left / width).coerceIn(0f, 1f),
                (bounds.top / height).coerceIn(0f, 1f),
                (bounds.right / width).coerceIn(0f, 1f),
                (bounds.bottom / height).coerceIn(0f, 1f),
            )
            if (!SceneRecognitionPolicy.isValidBox(normalized)) return@mapNotNull null
            ObjectObservation(
                box = normalized,
                labels = listOfNotNull(label),
                detectionConfidence = score,
                classificationConfidence = score.takeIf { label != null },
                category = SceneRecognitionPolicy.categoryFor(listOfNotNull(label)),
            )
        }
    }


    private fun ObjectObservation.remapFrom(crop: ObjectDetectionCrop): ObjectObservation = copy(
        box = NormalizedBox(
            (crop.left + box.left * crop.width).coerceIn(0f, 1f),
            (crop.top + box.top * crop.height).coerceIn(0f, 1f),
            (crop.left + box.right * crop.width).coerceIn(0f, 1f),
            (crop.top + box.bottom * crop.height).coerceIn(0f, 1f),
        ),
    )
}
