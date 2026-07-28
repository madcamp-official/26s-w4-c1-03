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
) : CustomSceneDetector, AcceleratorReporting {
    private data class DetectorHandle(
        val detector: ObjectDetector,
        val accelerator: DetectorAccelerator,
    )

    override val modelId: String = "efficientdet-lite0-coco-int8"

    private val appContext = context.applicationContext

    // B 모듈 리드 승인 수정(오너 결정 O-6, 2026-07-28): 델리게이트 결과를 기록으로 남긴다.
    //
    // preferGpu는 기본값도 true이고 guide_config.json도 true인데, 기기에서는 GPU가
    // 잡히지 않는다. 검출기 init 부근 logcat에 "Created TensorFlow Lite XNNPACK
    // delegate for CPU."만 있고 GPU 쪽 대응 문구는 한 번도 나오지 않는다 — 그 문구
    // ("Created TensorFlow Lite delegate for GPU.")는 실제로 배포된
    // libmediapipe_tasks_vision_jni.so 안에 문자열로 들어 있으므로, 없다는 사실
    // 자체가 GPU 초기화가 성공하지 못했다는 증거다.
    //
    // 문제는 강등이 아니라 침묵이다. 예전 코드는 델리게이트 실패를 지역 변수
    // lastFailure에 담았다가 뒤 델리게이트가 성공하는 순간 그대로 버렸고, 최종
    // 델리게이트는 아무도 읽지 않는 private 필드에만 남았다. 프레임당 가장 비싼
    // 단계가 GPU인지 CPU인지를 서드파티 로그의 노드 수로 추측해야 했다.
    @Volatile
    private var acceleratorState: DetectorAcceleratorReport =
        DetectorAcceleratorReport(requestedGpu = config.preferGpu, accelerator = null)

    override val acceleratorReport: DetectorAcceleratorReport
        get() = acceleratorState

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
    private var detector: DetectorHandle? = if (config.enabled) createInitialDetector() else null
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

        return ObjectDetectionBatch(merged, isFresh = true, sequenceId = sequenceId)
    }

    private fun empty() = ObjectDetectionBatch(emptyList(), isFresh = true, sequenceId = sequenceId)

    override fun detect(frame: AnalysisFrame): List<ObjectObservation> = detectBatch(frame).objects

    override fun close() {
        detector?.detector?.close()
    }

    /**
     * Tries each accelerator in [DetectorAcceleratorReport.plan] order and
     * **records which one won**.
     *
     * Every early-exit here writes [acceleratorState] before returning, because
     * the one thing this method must not do is leave the app guessing. The GPU
     * throwable is logged where it happens rather than accumulated into a
     * `lastFailure` that a later success discards — that discard is why a GPU
     * refusal has never once been visible in a device capture.
     */
    private fun createInitialDetector(): DetectorHandle? {
        var gpuFailure: Throwable? = null
        var lastFailure: Throwable? = null
        DetectorAcceleratorReport.plan(config.preferGpu).forEach { accelerator ->
            val attempt = runCatching { createDetector(appContext, accelerator) }
            val handle = attempt.getOrNull()
            if (handle != null) {
                acceleratorState = DetectorAcceleratorReport(
                    requestedGpu = config.preferGpu,
                    accelerator = accelerator,
                    gpuFailure = gpuFailure?.describe(),
                )
                // The line that makes the delegate discoverable without decompiling.
                // Info, not debug: this is true of release builds too, and a reader
                // chasing a slow frame should not have to rebuild to see it.
                Log.i(TAG, acceleratorState.format())
                return handle
            }
            lastFailure = attempt.exceptionOrNull()
            if (accelerator == DetectorAccelerator.GPU) {
                gpuFailure = lastFailure
                Log.w(TAG, "GPU delegate refused — falling back to CPU", gpuFailure)
            }
        }
        acceleratorState = DetectorAcceleratorReport(
            requestedGpu = config.preferGpu,
            accelerator = null,
            gpuFailure = gpuFailure?.describe(),
        )
        Log.w(
            TAG,
            "EfficientDet unavailable — object detection is off this session. " + acceleratorState.format(),
            lastFailure,
        )
        return null
    }

    private fun createDetector(context: Context, accelerator: DetectorAccelerator): DetectorHandle {
        val base = BaseOptions.builder()
            .setModelAssetPath(config.modelAsset)
            .setDelegate(accelerator.toDelegate())
            .build()
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.IMAGE)
            .setMaxResults(config.maxResults)
            .setScoreThreshold(config.minimumConfidence)
            .build()
        return DetectorHandle(ObjectDetector.createFromOptions(context, options), accelerator)
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
     * detector seam: retry once on CPU, then report no objects for this frame
     * rather than crashing the CameraX analyzer thread.
     */
    private fun detectBitmap(bitmap: Bitmap): List<ObjectObservation> {
        val current = detector ?: return emptyList()
        val first = runCatching { detectBitmap(current.detector, bitmap) }
        if (first.isSuccess) return first.getOrThrow()

        if (current.accelerator == DetectorAccelerator.GPU) {
            val cause = first.exceptionOrNull()
            Log.w(TAG, "GPU inference failed mid-session — rebuilding the detector on CPU", cause)
            runCatching { current.detector.close() }
            val rebuilt = runCatching { createDetector(appContext, DetectorAccelerator.CPU) }.getOrNull()
            detector = rebuilt
            // Both outcomes are recorded, including the bad one. `rebuilt == null`
            // used to leave `detector` null for the rest of the session with no log
            // at all: object detection simply stopped and every later frame returned
            // an empty batch that looked like "nothing in view".
            acceleratorState = DetectorAcceleratorReport(
                requestedGpu = config.preferGpu,
                accelerator = if (rebuilt != null) DetectorAccelerator.CPU else null,
                gpuFailure = cause?.describe(),
                runtimeDowngrade = true,
            )
            Log.w(TAG, acceleratorState.format())
            rebuilt?.let { cpu ->
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


    private fun DetectorAccelerator.toDelegate(): Delegate = when (this) {
        DetectorAccelerator.GPU -> Delegate.GPU
        DetectorAccelerator.CPU -> Delegate.CPU
    }

    /**
     * Class name plus message. `toString()` alone would be enough for most
     * throwables, but MediaPipe's `MediaPipeException` prints a multi-line graph
     * dump, and this string has to survive into a one-line log record and a HUD
     * field.
     */
    private fun Throwable.describe(): String =
        "${this::class.java.name}: ${message?.lineSequence()?.firstOrNull()?.trim().orEmpty()}"

    private fun ObjectObservation.remapFrom(crop: ObjectDetectionCrop): ObjectObservation = copy(
        box = NormalizedBox(
            (crop.left + box.left * crop.width).coerceIn(0f, 1f),
            (crop.top + box.top * crop.height).coerceIn(0f, 1f),
            (crop.left + box.right * crop.width).coerceIn(0f, 1f),
            (crop.top + box.bottom * crop.height).coerceIn(0f, 1f),
        ),
    )
}
