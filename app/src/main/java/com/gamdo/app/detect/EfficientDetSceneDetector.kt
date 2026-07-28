package com.gamdo.app.detect

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector

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
    private val fallback = MlKitObjectDetector(config.fallback)
    private var detector: DetectorHandle? = if (config.enabled) {
        runCatching { createDetector(appContext) }.getOrNull()
    } else {
        null
    }
    private var frameCount = 0
    private var sequenceId = 0L

    override fun detectBatch(frame: AnalysisFrame): ObjectDetectionBatch {
        if (detector == null) return fallback.detectBatch(frame)
        frameCount++
        sequenceId++
        val provider = frame.cropBitmapProvider ?: return fallback.detectBatch(frame)
        val full = runCatching {
            provider(ObjectDetectionCrop(0f, 0f, 1f, 1f))
        }.getOrNull() ?: return fallback.detectBatch(frame)

        val primary = try {
            filterRawCandidates(detectBitmap(full))
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
                        secondary = filterRawCandidates(detectBitmap(cropped)).map { it.remapFrom(crop) },
                        duplicateIou = config.fallback.duplicateIou,
                    )
                } finally {
                    if (!cropped.isRecycled) cropped.recycle()
                }
            }
        } else {
            primary
        }

        return if (merged.isEmpty() || merged.none(::isCentralCandidate)) {
            fallback.detectBatch(frame).copy(isFresh = true, sequenceId = sequenceId)
        } else {
            ObjectDetectionBatch(merged, isFresh = true, sequenceId = sequenceId)
        }
    }

    override fun detect(frame: AnalysisFrame): List<ObjectObservation> = detectBatch(frame).objects

    override fun close() {
        detector?.detector?.close()
        fallback.close()
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
        // Crops are a recovery path for a genuinely sparse full-frame result.
        // Running them for a 2-object scene was the source of the 7~11-box
        // inflation seen on the device. The tracker performs the final ROI and
        // duplicate policy; this cheap gate only decides whether to spend time
        // on a second inference.
        val centralCandidates = primary.count { SceneInterestRegion.Default.contains(it.box) }
        return centralCandidates <= 1
    }

    private fun filterRawCandidates(objects: List<ObjectObservation>): List<ObjectObservation> =
        objects.filter { candidate ->
            val box = candidate.box
            val area = box.width * box.height
            val touches = listOf(
                box.left <= 0.02f, box.top <= 0.02f,
                box.right >= 0.98f, box.bottom >= 0.98f,
            ).count { it }
            val aspect = maxOf(box.width, box.height) / minOf(box.width, box.height).coerceAtLeast(0.0001f)
            area in 0.01f..0.85f &&
                !(touches >= 2 && area >= 0.08f) &&
                !(candidate.category == GuideObjectCategory.UNKNOWN && aspect > 3.5f)
        }

    /**
     * Some Samsung GPU drivers can initialise the delegate successfully and
     * still fail on a later GL buffer map. Keep that native failure inside the
     * detector seam: retry once on CPU, then let the regular ML Kit fallback
     * handle this frame rather than crashing the CameraX analyzer thread.
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

    private fun isCentralCandidate(candidate: ObjectObservation): Boolean {
        val dx = candidate.box.centerX - 0.5f
        val dy = candidate.box.centerY - 0.5f
        return dx * dx + dy * dy <= 0.36f * 0.36f
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
