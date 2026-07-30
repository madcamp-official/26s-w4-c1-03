package com.gamdo.app.detect

import android.content.Context
import android.graphics.Bitmap
import android.os.Process
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val TAG = "EfficientDet"

/**
 * Named so a device trace says which thread spent 7.5s, rather than
 * `pool-3-thread-1`, and so the GPU inference thread is identifiable once the
 * upgrade is adopted and that same thread starts running one inference per frame.
 */
private const val GPU_UPGRADE_THREAD_NAME = "gamdo-gpu-upgrade"

/** Lite2 uses a 448px input tensor; the runtime performs the final resize. */
private const val VALIDATION_INPUT_PX = 448

/** Mid grey. Opaque and non-zero so nothing upstream can shortcut an empty image. */
private const val VALIDATION_FILL = 0xFF808080.toInt()

/** Runtime knobs for the bundled mobile object detector. */
data class EfficientDetSceneDetectorConfig(
    val enabled: Boolean = true,
    val modelAsset: String = "models/efficientdet_lite2_coco_int8.tflite",
    val fallbackModelAsset: String = "models/efficientdet_lite0_coco_int8.tflite",
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
    val scopeStore = SceneSearchScopeStore()
    private val scopedTracks = ObjectTrackManager()
    /** Result of an explicit lasso refinement. It is intentionally separate
     * from the live full-frame batch so callers cannot accidentally count a
     * refinement twice as ordinary frame evidence. */
    data class ScopedDetectionResult(
        val objects: List<ObjectObservation>,
        val scopeRevision: Long,
        val ran: Boolean,
    )
    /**
     * A live detector plus the accelerator it is running on.
     *
     * [confined] is unconfined for the CPU detector (built and used on the analysis
     * thread) and thread-confined for an upgraded GPU one — see [ThreadConfined].
     */
    private class DetectorHandle(
        val confined: ThreadConfined<ObjectDetector>,
        val accelerator: DetectorAccelerator,
    ) {
        fun close() = confined.close { runCatching { it.close() } }
    }

    override val modelId: String = "efficientdet-lite2-coco-int8"

    private val appContext = context.applicationContext

    // B 모듈 리드 승인 수정(오너 결정 O-6, 2026-07-28): 델리게이트 결과를 기록으로 남긴다.
    //
    // 문제는 강등이 아니라 침묵이었다. 예전 코드는 델리게이트 실패를 지역 변수
    // lastFailure에 담았다가 뒤 델리게이트가 성공하는 순간 그대로 버렸고, 최종
    // 델리게이트는 아무도 읽지 않는 private 필드에만 남았다. 프레임당 가장 비싼
    // 단계가 GPU인지 CPU인지를 서드파티 로그의 노드 수로 추측해야 했다.

    // B 모듈 리드 승인 수정(remain_plan W3-4, 2026-07-29): CPU 선행 + GPU 후행 검증.
    //
    // 위 O-6 주석은 "기기에서 GPU 초기화 자체가 실패한다"고 적고 있었고, 근거는
    // logcat에 "Created TensorFlow Lite delegate for GPU."가 없다는 것이었다.
    // **그 추론은 틀렸다.** O-6이 심은 기록이 실제로 찍히자 SM-G970N에서 이렇게
    // 나왔다(2026-07-29, 3회 재현):
    //
    //   10:51:29.590 I/EfficientDet: accelerator=GPU requested=GPU degraded=false
    //   10:51:29.606 D/CameraStartup: detectorBuild … object=7570 total=7630ms
    //   10:51:31.320 W/EfficientDet: GPU inference failed mid-session
    //   10:51:31.431 W/EfficientDet: accelerator=CPU degraded=true runtimeDowngrade=true
    //
    // 생성은 성공한다. 실패하는 것은 **첫 추론**이고, 원인은 gl_interop.cc의
    // [GL_INVALID_VALUE]: glMapBufferRange다. 서드파티 라이브러리가 로그를 남길
    // 의무는 없으므로 "문구가 없다"는 증거가 아니었다.
    //
    // 그래서 콜드 스타트가 7.6초 동안 만든 검출기를 1.7초 뒤에 버렸다. 격리된
    // worktree에서 CPU 전용으로 콜드 프로세스를 2회 재면 object=281/228ms,
    // total=376/313ms — 같은 4.5MB 에셋을 같은 콜드 캐시에서 읽는다. 즉 7.5초는
    // 모델 파일 읽기가 아니라 **GPU 델리게이트 컴파일**이다.
    //
    // preferGpu=false 전역화는 답이 아니다. 담당 B 환경에서는 GPU가 정상 동작한다
    // (오너 확인, 2026-07-29). 그래서 선택을 끄는 대신 **순서를 바꿨다**:
    // CPU로 먼저 만들어 가이드를 ~350ms에 띄우고(GpuUpgradePolicy.coldStart),
    // GPU는 별도 스레드에서 만들어 **실제 추론 1회가 성공한 경우에만** 교체한다.
    @Volatile
    private var acceleratorState: DetectorAcceleratorReport =
        DetectorAcceleratorReport(requestedGpu = config.preferGpu, accelerator = null)

    /** Guards the transitions of [acceleratorState] and [upgraded] across two threads. */
    private val lock = Any()

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
    private var detector: DetectorHandle? = if (config.enabled) createColdStartDetector() else null

    /**
     * A validated GPU detector waiting to be swapped in, published by the upgrade
     * thread and consumed by the analysis thread.
     *
     * The swap is deliberately **not** performed by the thread that built it. The
     * analysis thread is mid-`detect()` on the CPU detector for most of a frame, and
     * closing a MediaPipe handle out from under a running inference is a native
     * use-after-free. Handing it over instead means the only thread that ever
     * mutates [detector], or closes a detector that has served a frame, is the one
     * that uses it — the same invariant `AnalysisThreadResource` relies on.
     */
    @Volatile
    private var upgraded: DetectorHandle? = null

    @Volatile
    private var closed = false

    private var frameCount = 0
    private var sequenceId = 0L
    private val detectionFusion = DetectionFusion()
    private val objectTracks = ObjectTrackManager()

    init {
        if (config.enabled) startGpuUpgradeIfWanted()
    }

    override fun detectBatch(frame: AnalysisFrame): ObjectDetectionBatch {
        adoptPendingUpgrade()
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

        val scope = scopeStore.current()
        if (scope is DetectionSearchScope.Polygon) {
            val refined = detectPolygon(frame, scope.region, scope.revision)
            if (refined.ran && refined.scopeRevision == scope.revision) {
                val scoped = refined.objects.map { observation ->
                    SceneObjectCandidate(observation.box, observation.detectionConfidence ?: 0f, observation.category, observation.classificationConfidence, DetectionSource.SCOPE_CROP)
                }
                val trackedScoped = scopedTracks.update(scope.revision, scoped)
                    .filter { scope.region.accepts(it.box, .50f) }.take(4)
                val scopedObservations = trackedScoped.map { ObjectObservation(it.box, detectionConfidence = it.confidence, category = it.category, sceneTrackId = it.trackId) }
                return ObjectDetectionBatch(scopedObservations, true, sequenceId)
            }
        }
        val fused = detectionFusion.fuse(merged.map {
            SceneObjectCandidate(
                box = it.box,
                detectionConfidence = it.detectionConfidence ?: it.confidence,
                category = it.category,
                classificationConfidence = it.classificationConfidence,
                source = DetectionSource.FULL_FRAME,
                nativeTrackingId = it.trackingId,
            )
        })
        val tracked = objectTracks.update(sequenceId, fused).map { track ->
            ObjectObservation(
                box = track.box,
                detectionConfidence = track.confidence,
                classificationConfidence = null,
                category = track.category,
                sceneTrackId = track.trackId,
            )
        }
        return ObjectDetectionBatch(tracked, isFresh = true, sequenceId = sequenceId)
    }

    private fun empty() = ObjectDetectionBatch(emptyList(), isFresh = true, sequenceId = sequenceId)

    override fun detect(frame: AnalysisFrame): List<ObjectObservation> = detectBatch(frame).objects

    fun detectPolygon(
        frame: AnalysisFrame,
        polygon: com.gamdo.app.guide.ScenePolygonRegion,
        scopeRevision: Long,
        padding: Float = .03f,
    ): ScopedDetectionResult {
        val provider = frame.cropBitmapProvider ?: return ScopedDetectionResult(emptyList(), scopeRevision, false)
        val crop = ScopeCropResolver.forPolygon(polygon, padding)
        val bitmap = runCatching {
            provider(ObjectDetectionCrop(crop.left, crop.top, crop.right, crop.bottom))
        }.getOrNull() ?: return ScopedDetectionResult(emptyList(), scopeRevision, false)
        val masked = runCatching {
            PolygonBitmapMasker().maskOutsidePolygon(bitmap, polygon, crop)
        }.getOrNull() ?: bitmap
        return try {
            val refined = detectBitmap(masked).map { observation ->
                observation.copy(
                    box = NormalizedBox(
                        (crop.left + observation.box.left * crop.width).coerceIn(0f, 1f),
                        (crop.top + observation.box.top * crop.height).coerceIn(0f, 1f),
                        (crop.left + observation.box.right * crop.width).coerceIn(0f, 1f),
                        (crop.top + observation.box.bottom * crop.height).coerceIn(0f, 1f),
                    ),
                )
            }.filter { polygon.accepts(it.box, minimumBoxOverlap = .50f) }
            ScopedDetectionResult(refined, scopeRevision, true)
        } finally {
            if (!masked.isRecycled && masked !== bitmap) masked.recycle()
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    override fun close() {
        closed = true
        // Read again under the lock: the upgrade thread may be publishing right now,
        // and a GPU detector published after teardown would never be closed at all.
        synchronized(lock) { upgraded.also { upgraded = null } }?.close()
        detector?.close()
        detector = null
        objectTracks.reset()
    }

    /**
     * Builds the detector the first frame will use — **on the CPU, always**.
     *
     * This method is the 7.5s. It used to walk `DetectorAcceleratorReport.plan`,
     * which puts GPU first, so on SM-G970N it compiled a GPU delegate for 7570ms
     * before the analysis thread could look at a single frame, and that delegate was
     * discarded 1.7s later when its first inference threw. A cold CPU build of the
     * same 4.5MB asset is ~250ms (281 / 228ms measured, cold process).
     *
     * The GPU is not abandoned, only deferred — see [startGpuUpgradeIfWanted].
     */
    private fun createColdStartDetector(): DetectorHandle? {
        val accelerator = GpuUpgradePolicy.coldStart
        val attempt = runCatching {
            DetectorHandle(ThreadConfined.unconfined(createDetector(appContext, accelerator, config.modelAsset)), accelerator)
        }.recoverCatching {
            Log.w(TAG, "primary model unavailable; using Lite0 fallback", it)
            DetectorHandle(ThreadConfined.unconfined(createDetector(appContext, accelerator, config.fallbackModelAsset)), accelerator)
        }
        val handle = attempt.getOrNull()
        acceleratorState = DetectorAcceleratorReport(
            requestedGpu = config.preferGpu,
            accelerator = handle?.accelerator,
            upgrade = GpuUpgradePolicy.initialStage(config.preferGpu, handle?.accelerator),
        )
        if (handle == null) {
            Log.w(
                TAG,
                "EfficientDet unavailable — object detection is off this session. " +
                    acceleratorState.format(),
                attempt.exceptionOrNull(),
            )
            return null
        }
        // The line that makes the delegate discoverable without decompiling. Info,
        // not debug: this is true of release builds too, and a reader chasing a slow
        // frame should not have to rebuild to see it.
        Log.i(TAG, acceleratorState.format())
        return handle
    }

    /**
     * Pursues the GPU delegate off the critical path, and adopts it **only if a real
     * inference on it returns**.
     *
     * Three things about this method are load-bearing, and each of them is the
     * device's doing rather than a preference:
     *
     *  - **Its own thread.** Queued on the analysis executor, a 7.5s build blocks
     *    every frame behind it — the stall would move, not go away.
     *  - **Background priority while building.** 7.5s of delegate compilation
     *    competing with the guide on a 2019 mid-range phone is visible. The priority
     *    is restored before the detector is published, because that same thread then
     *    runs one inference per frame.
     *  - **A validation inference.** `createFromOptions` returned a usable-looking
     *    handle on the very device where the delegate does not work, and
     *    `accelerator=GPU degraded=false` was logged about it. Creation proves
     *    nothing here.
     *
     * Runs at most once per detector: [GpuUpgradePolicy.shouldAttempt] only permits
     * [GpuUpgradeStage.PENDING], and no outcome resolves back to it. A driver that
     * refuses once refuses in a loop, at 7.5s a turn.
     */
    private fun startGpuUpgradeIfWanted() {
        if (!GpuUpgradePolicy.shouldAttempt(acceleratorState.upgrade)) return
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, GPU_UPGRADE_THREAD_NAME)
        }
        executor.execute {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            val startNs = System.nanoTime()
            val built = runCatching { createDetector(appContext, DetectorAccelerator.GPU) }
            val gpu = built.getOrNull()
            if (gpu == null) {
                refuseUpgrade(executor, GpuUpgradeStage.CREATE_FAILED, built.exceptionOrNull(), startNs)
                return@execute
            }
            // Confined here, on this thread, so the inference that validates it and
            // every inference that follows run on the same thread it was built on.
            val confined = ThreadConfined.confinedTo(executor, gpu)
            val validation = runCatching { confined.use(::validateGpu) }
            if (validation.isFailure) {
                confined.close { runCatching { it.close() } }
                refuseUpgrade(
                    executor = null, // close() above already shut the thread down
                    stage = GpuUpgradeStage.VALIDATION_FAILED,
                    cause = validation.exceptionOrNull(),
                    startNs = startNs,
                )
                return@execute
            }
            // Back to the analysis thread's priority: from here this thread is a
            // per-frame inference thread, not a build thread.
            Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
            publishUpgrade(DetectorHandle(confined, DetectorAccelerator.GPU), startNs)
        }
    }

    /**
     * One real inference on a synthetic frame.
     *
     * [VALIDATION_INPUT_PX] is EfficientDet-Lite0's own input size, so this runs the
     * same tensor path a camera frame does without an extra resize, for 410KB. The
     * result is discarded — the only question is whether `detect()` returns, and on
     * SM-G970N it does not: `[GL_INVALID_VALUE]: glMapBufferRange`.
     */
    private fun validateGpu(model: ObjectDetector) {
        val probe = Bitmap.createBitmap(
            VALIDATION_INPUT_PX,
            VALIDATION_INPUT_PX,
            Bitmap.Config.ARGB_8888,
        )
        try {
            probe.eraseColor(VALIDATION_FILL)
            model.detect(BitmapImageBuilder(probe).build())
        } finally {
            if (!probe.isRecycled) probe.recycle()
        }
    }

    /** The GPU is validated and handed to the analysis thread to swap in. */
    private fun publishUpgrade(handle: DetectorHandle, startNs: Long) {
        val orphaned = synchronized(lock) {
            if (closed) return@synchronized handle
            upgraded = handle
            null
        }
        if (orphaned != null) {
            orphaned.close()
            return
        }
        Log.i(TAG, "gpuUpgrade validated in ${elapsedMs(startNs)}ms on $GPU_UPGRADE_THREAD_NAME")
    }

    /** The GPU was refused. CPU keeps serving and nothing is retried. */
    private fun refuseUpgrade(
        executor: ExecutorService?,
        stage: GpuUpgradeStage,
        cause: Throwable?,
        startNs: Long,
    ) {
        executor?.shutdown()
        synchronized(lock) {
            acceleratorState = acceleratorState.refusingGpu(stage, cause?.describe())
        }
        Log.w(
            TAG,
            "gpuUpgrade refused after ${elapsedMs(startNs)}ms — staying on CPU. " +
                acceleratorState.format(),
            cause,
        )
    }

    /**
     * Swaps a validated GPU detector in, on the analysis thread, between frames.
     *
     * The CPU detector is closed here rather than kept as a warm spare: it is ~5MB
     * of native memory that a revoke can rebuild in ~250ms, and `DetectorWarmupGate`
     * already treats that 5MB as worth releasing when nobody is looking at it.
     */
    private fun adoptPendingUpgrade() {
        if (upgraded == null) return
        val pending = synchronized(lock) { upgraded.also { upgraded = null } } ?: return
        if (closed) {
            pending.close()
            return
        }
        val previous = detector
        detector = pending
        previous?.close()
        synchronized(lock) { acceleratorState = acceleratorState.adoptingGpu() }
        Log.i(TAG, acceleratorState.format())
    }

    private fun createDetector(
        context: Context,
        accelerator: DetectorAccelerator,
        assetPath: String = config.modelAsset,
    ): ObjectDetector {
        val base = BaseOptions.builder()
            .setModelAssetPath(assetPath)
            .setDelegate(accelerator.toDelegate())
            .build()
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.IMAGE)
            .setMaxResults(config.maxResults)
            .setScoreThreshold(config.minimumConfidence)
            .build()
        return ObjectDetector.createFromOptions(context, options)
    }

    private fun elapsedMs(startNs: Long): Long = (System.nanoTime() - startNs) / 1_000_000L

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
     * A GPU that passed validation and then faulted anyway.
     *
     * Validation is evidence, not a guarantee: a driver can serve inferences for
     * minutes and then fail a GL buffer map under memory pressure or after another
     * app takes the GPU. Keep that native failure inside the detector seam — rebuild
     * once on CPU, never re-attempt the GPU, and report no objects for this frame
     * rather than crashing the CameraX analyzer thread.
     */
    private fun detectBitmap(bitmap: Bitmap): List<ObjectObservation> {
        val current = detector ?: return emptyList()
        // The bitmap is created on the analysis thread and, for a confined GPU
        // detector, read on the upgrade thread. Safe: `use` blocks the caller for the
        // whole read, so there is no concurrent access and no recycle underneath it.
        val first = runCatching { current.confined.use { detectBitmap(it, bitmap) } }
        if (first.isSuccess) return first.getOrThrow()

        if (current.accelerator == DetectorAccelerator.GPU) {
            val cause = first.exceptionOrNull()
            Log.w(TAG, "GPU inference failed mid-session — rebuilding the detector on CPU", cause)
            current.close()
            val rebuilt = runCatching {
                DetectorHandle(
                    ThreadConfined.unconfined(createDetector(appContext, DetectorAccelerator.CPU)),
                    DetectorAccelerator.CPU,
                )
            }.getOrNull()
            detector = rebuilt
            // Both outcomes are recorded, including the bad one. `rebuilt == null`
            // used to leave `detector` null for the rest of the session with no log
            // at all: object detection simply stopped and every later frame returned
            // an empty batch that looked like "nothing in view".
            synchronized(lock) {
                acceleratorState = acceleratorState.revokingGpu(cause?.describe())
                    .copy(accelerator = rebuilt?.accelerator)
            }
            Log.w(TAG, acceleratorState.format())
            rebuilt?.let { cpu ->
                return runCatching { cpu.confined.use { detectBitmap(it, bitmap) } }
                    .getOrDefault(emptyList())
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
