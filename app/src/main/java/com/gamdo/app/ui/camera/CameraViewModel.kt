package com.gamdo.app.ui.camera

import com.gamdo.app.BuildConfig
import com.gamdo.app.camera.AnalysisStats
import com.gamdo.app.camera.PreviewFrameMeter
import com.gamdo.app.camera.PreviewStats
import com.gamdo.app.camera.TiltReading
import com.gamdo.app.detect.BrightnessSample
import com.gamdo.app.detect.DetectionResult
import com.gamdo.app.detect.FrameFeatures
import com.gamdo.app.detect.FrameFeatureInput
import com.gamdo.app.guide.AlignmentEngine
import com.gamdo.app.guide.GuideConfigBundle
import com.gamdo.app.guide.MatchScoreCalculator
import com.gamdo.app.guide.OverlayStabilizer
import com.gamdo.app.guide.StyleTarget
import com.gamdo.app.guide.SceneFrameSignals
import com.gamdo.app.guide.SceneGuideCoordinator
import com.gamdo.app.guide.SceneGuideSessionController
import com.gamdo.app.guide.GuideLayoutState
import com.gamdo.app.guide.LayoutTemplateCatalog
import com.gamdo.app.guide.LayoutTemplateSummary
import com.gamdo.app.detect.StableSceneTracker
import com.gamdo.app.guide.SceneLayoutGuide
import com.gamdo.app.guide.FixedLayoutGuide
import com.gamdo.app.guide.toProjection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Debug HUD snapshot of the guide chain. `matchScore` is KPI/log-only and must
 * never reach the product UI (D2) — the badge that renders this is compiled
 * behind `BuildConfig.DEBUG`.
 */
data class GuideDebug(
    val features: FrameFeatures,
    val aligned: Boolean,
    val visible: Boolean,
    val iou: Float,
    val matchScore: Float,
    /**
     * Which layout template the auto resolver has latched, or null for none.
     *
     * Distinct from the HUD's `layout=` field, which is the *outline confidence
     * level*. The two were indistinguishable on device and lead to opposite
     * conclusions about whether the preset guide should be on screen, which made
     * "did the coexistence fix work?" unanswerable from a screenshot.
     */
    val fixedLayoutId: String? = null,
)

/**
 * What §3-3 records at the shutter: the analysis state of the last frame before
 * the user pressed it.
 *
 * Separate from [GuideDebug] on purpose. `GuideDebug` is collected only when
 * `collectDebugSignals` is on, i.e. debug builds — and a KPI that exists only in
 * debug builds records nothing from the device the numbers are actually about.
 * This one is populated on every frame regardless of build type.
 *
 * `matchScore` is deliberately **not** a field. It is a weighted computation
 * needed once per photo, not twelve times a second; call [CameraViewModel.matchScoreOf]
 * at the shutter instead. Note this is the §4.2 weighted score, not
 * `AlignmentEngine`'s IoU — same word, different quantity.
 */
data class ShutterFrame(
    val features: FrameFeatures,
    val target: StyleTarget,
    /**
     * Whether the subject was inside the preset bracket, or **null when a fixed
     * layout was latched** — the bracket is not drawn then, so there is nothing
     * the user was aiming at. "NULL=측정불가" is the schema's own vocabulary
     * (DB 스키마 v2.0 §session_guides.resolved); this field follows it.
     */
    val aligned: Boolean?,
    /** Raw overlay visibility. The show/hide KPI measures exactly this. */
    val visible: Boolean,
    val fixedLayout: FixedLayoutGuide? = null,
    /**
     * Where the scene's dominant horizontal line actually sat, or null if none was
     * detected with enough confidence.
     *
     * `MatchScoreCalculator` weights the horizon at 15% of the §4.2 score and takes
     * this as a parameter — but no caller ever passed one, so the parameter kept its
     * `0.5f` default and 15% of every score was a function of the *preset alone*,
     * identical for every photo. Carrying it here is what makes that term a
     * measurement (review_report M2).
     */
    val observedHorizonPosition: Float? = null,
)

/**
 * Rolling cost of [com.gamdo.app.detect.FrameFeatureCalculator.calculate] against
 * the §2-4 30ms budget. Measurement only — the budget is never enforced, because
 * silently skipping feature extraction would break the guide instead of the log.
 */
data class FeatureBudgetStats(
    val frames: Long,
    val lastMs: Double,
    val meanMs: Double,
    val maxMs: Double,
    val budgetMs: Double,
    val overBudgetFrames: Long,
) {
    val withinBudget: Boolean get() = overBudgetFrames == 0L
}

/** Aggregated, privacy-safe quality evidence for the current camera visit. */
data class SceneGuideMetrics(
    val freshObjectFrames: Long = 0,
    val objectFps: Double = 0.0,
    val firstLayoutMs: Double? = null,
    val selectedTemplateId: String? = null,
    val signature: String? = null,
)

/**
 * State holder for the camera screen (P1 §3-1) — **the analysis-thread → UI
 * boundary lives here and nowhere else**.
 *
 * [onFrameAnalyzed] and [onStats] are called from the CameraX analysis executor;
 * every result is published through a [StateFlow] that Compose collects on the
 * main thread. Nothing in this class touches Android, CameraX or ML Kit, so the
 * whole reduction path is JVM-unit-testable — which is what lets the §0.4
 * overlay-stability harness drive the real production path instead of a
 * simulator (see `OverlayStabilityHarness` in the test source set).
 *
 * Per-frame chain: `FrameFeatureCalculator` → `AlignmentEngine` → [OverlayStabilizer]
 * → [OverlayData]. The first two are frozen 담당 B modules used call-site only;
 * the stabilizer is this vertical's display-side damping (§3-2 "깜빡임 금지").
 *
 * Every threshold arrives via [config], sourced from `assets/guide_config.json`
 * (CFG-1) — code defaults are fallbacks only.
 *
 * @param logSink where the §2-4 budget line goes. Defaults to a no-op so this
 *   class stays free of `android.util.Log`; the host wires it up.
 */
class CameraViewModel(
    private val config: GuideConfigBundle = GuideConfigBundle(),
    private val alignmentEngine: AlignmentEngine = AlignmentEngine(),
    private val matchScoreCalculator: MatchScoreCalculator = MatchScoreCalculator(),
    /** Debug signal collection; off in release so the guide chain costs nothing. */
    private val collectDebugSignals: Boolean = BuildConfig.DEBUG,
    private val logSink: (String) -> Unit = {},
) {
    private var detector: com.gamdo.app.detect.SceneDetector? = null

    fun attachDetector(detector: com.gamdo.app.detect.SceneDetector) {
        if (this.detector === detector) return
        this.detector = detector
        detector.setObjectDetectionPaused(false)
    }

    private val guideConfig = config.toGuideConfig()
    private val featureCalculator = config.toFrameFeatureCalculator()
    private val stabilizer = OverlayStabilizer(config.toStabilizerConfig())
    private val sceneGuideSessionController = SceneGuideSessionController(
        coordinator = SceneGuideCoordinator(
            templateSafetyMargin = config.objectGuide.templateSafetyMargin,
            detectedSlotShapeConfig = config.objectGuide.toDetectedSlotShapeConfig(),
            referenceGraceFrames = config.objectGuide.referenceGraceFrames,
        ),
        tracker = StableSceneTracker(config.toObjectTrackerConfig()),
    )
    private val budgetMs = config.features.analysisBudgetMs
    private val logEveryFrames = config.features.budgetLogEveryFrames

    private var budgetFrames = 0L
    private var budgetSumMs = 0.0
    private var budgetMaxMs = 0.0
    private var budgetOverFrames = 0L
    private var sceneStartedNs = 0L
    private var firstFixedNs: Long? = null
    private var freshObjectFrames = 0L

    private val _stats = MutableStateFlow<AnalysisStats?>(null)
    val stats: StateFlow<AnalysisStats?> = _stats.asStateFlow()

    /** W3-2 preview rate. Null until a source is attached *and* a window closes. */
    private val previewMeter = PreviewFrameMeter()

    private val _previewStats = MutableStateFlow<PreviewStats?>(null)
    val previewStats: StateFlow<PreviewStats?> = _previewStats.asStateFlow()

    /**
     * Why the preview rate cannot currently be read, or null while it can.
     *
     * Carried so the HUD can say "not measured, and here is why" rather than show
     * a zero. A zero is indistinguishable from a frozen preview, and W3-2 exists
     * precisely because a number that did not mean what it was labelled went
     * unnoticed.
     */
    private val _previewFpsAvailability = MutableStateFlow(PreviewFpsAvailability.NOT_ATTACHED)
    val previewFpsAvailability: StateFlow<PreviewFpsAvailability> =
        _previewFpsAvailability.asStateFlow()

    private val _detectionLabel = MutableStateFlow("")
    val detectionLabel: StateFlow<String> = _detectionLabel.asStateFlow()

    private val _overlay = MutableStateFlow<OverlayData?>(null)
    val overlay: StateFlow<OverlayData?> = _overlay.asStateFlow()

    private val _guideDebug = MutableStateFlow<GuideDebug?>(null)
    val guideDebug: StateFlow<GuideDebug?> = _guideDebug.asStateFlow()

    /** §3-3 shutter snapshot source. Populated in release builds too — see [ShutterFrame]. */
    private val _lastFrame = MutableStateFlow<ShutterFrame?>(null)
    val lastFrame: StateFlow<ShutterFrame?> = _lastFrame.asStateFlow()

    /**
     * The §4.2 weighted match score for [frame], computed on demand.
     *
     * Called once per capture rather than once per frame: the shutter is the only
     * consumer, and paying for it at 12Hz to have it ready would be twelve times
     * the cost for the same one number.
     */
    fun matchScoreOf(frame: ShutterFrame): Float =
        matchScoreCalculator.calculate(
            frame.features,
            frame.target,
            // Falling back to the target's own horizon makes the term score 1.0,
            // i.e. no penalty. That is the honest reading: the term asks "did the
            // user place the horizon where this style wants it", and when no
            // horizon is visible the question does not apply. It also matches what
            // `SceneProposalEngine` already does with an undetected horizon
            // (`scene.horizonPosition ?: return style.horizonPosition`).
            //
            // The previous behaviour — leaving the parameter at its 0.5f default —
            // was worse than either: it compared the preset against a number no one
            // measured, so a style wanting a horizon at 0.55 was permanently docked
            // for a scene that may well have had it exactly there.
            observedHorizonPosition = frame.observedHorizonPosition ?: frame.target.horizonPosition,
        )

    private val _featureBudget = MutableStateFlow<FeatureBudgetStats?>(null)
    val featureBudget: StateFlow<FeatureBudgetStats?> = _featureBudget.asStateFlow()

    private val _styleTarget = MutableStateFlow(StyleTarget())
    val styleTarget: StateFlow<StyleTarget> = _styleTarget.asStateFlow()

    val layoutState: StateFlow<GuideLayoutState> = sceneGuideSessionController.layoutState
    val availableManualLayouts: List<LayoutTemplateSummary> = sceneGuideSessionController.availableManualLayouts

    private val _sceneGuideMetrics = MutableStateFlow(SceneGuideMetrics())
    val sceneGuideMetrics: StateFlow<SceneGuideMetrics> = _sceneGuideMetrics.asStateFlow()

    /**
     * Work handed from the main thread to the analysis thread (review_report #18).
     *
     * `AlignmentEngine`, `OverlayStabilizer` and `SceneGuideSessionController` hold
     * plain `ArrayDeque`s and plain fields, and [onFrameAnalyzed] mutates all of
     * them on CameraX's analysis executor. Every main-thread entry point that used
     * to reset that state — [setStyleTarget] from `LaunchedEffect(activePreset)`,
     * [rescanLayout] from the 재탐색 button's `onClick` — was writing across a
     * thread boundary with no synchronization at all.
     *
     * A JVM stress test (`CameraViewModelConcurrencyTest`) reproduced two distinct
     * failures on the pre-fix code, every run: `ConcurrentModificationException`
     * from the smoothing deque, and — the quieter one — a published target frame of
     * `RectN(NaN, NaN, NaN, NaN)` from a torn read mid-`clear()`. On device that is
     * an overlay whose geometry is garbage after the user taps a different style.
     *
     * Rather than lock every touch point, the mutation is **confined to one
     * thread**: callers enqueue, and [onFrameAnalyzed] drains at the top of the
     * frame it is already running on. Nothing needs a lock because nothing else
     * writes. The cost is that a reset lands on the next analyzed frame instead of
     * instantly — about 80ms, invisible next to the 180ms the frame itself takes,
     * and the reset only matters relative to the frames that follow it anyway.
     *
     * If no frames are arriving (camera detached), commands simply wait. That is
     * correct: there is no guide state to reset while nothing is being analysed,
     * and [onAnalyzerDetached] clears the queue along with everything else.
     */
    private val pendingGuideWork = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()

    /** Applies whatever the main thread asked for. Analysis thread only. */
    private fun drainPendingGuideWork() {
        while (true) {
            val work = pendingGuideWork.poll() ?: return
            work()
        }
    }

    /**
     * Swaps the composition target. A preset switch invalidates the smoothing
     * window, the last stable target and the display damping, so both stages are
     * reset with it — otherwise the new bracket would crawl out of the old one.
     */
    fun setStyleTarget(target: StyleTarget) {
        // Safe to publish immediately — `MutableStateFlow` is thread-safe and the
        // UI reads it. Everything below it is not, so it is deferred to the
        // analysis thread; see [pendingGuideWork].
        _styleTarget.value = target
        pendingGuideWork.add {
            alignmentEngine.reset()
            stabilizer.reset()
            // A style changes only spacing/scale/anchor of a fixed template. It
            // never makes the camera rediscover the scene.
            sceneGuideSessionController.updateStyle(target)
        }
    }

    /** Called from the analysis executor once per second. */
    fun onStats(stats: AnalysisStats) {
        _stats.value = stats
    }

    /**
     * One delivered preview frame (W3-2).
     *
     * Called on the **main thread**, unlike [onStats] and [onFrameAnalyzed]:
     * `PreviewView`'s frame-update callback is attached with a direct executor and
     * fires from `TextureView.onSurfaceTextureUpdated`. [previewMeter] is touched
     * from nowhere else, so the two threads never meet.
     */
    fun onPreviewFrame(nowNs: Long) {
        previewMeter.onFrame(nowNs)?.let { _previewStats.value = it }
    }

    /** Records whether a preview-frame source could be attached, and why not. */
    fun onPreviewFpsAvailability(availability: PreviewFpsAvailability) {
        _previewFpsAvailability.value = availability
        if (availability != PreviewFpsAvailability.MEASURING) {
            previewMeter.reset()
            _previewStats.value = null
        }
    }

    /**
     * Reduces one analyzed frame into overlay state. Called on the analysis
     * thread; publishes to Compose through StateFlow.
     *
     * [frameWidth]/[frameHeight] are the upright analysis dimensions used to map
     * normalized coordinates onto the preview.
     */
    fun onFrameAnalyzed(
        detection: DetectionResult,
        tilt: TiltReading,
        brightness: BrightnessSample,
        shake: Float,
        frameWidth: Int,
        frameHeight: Int,
        mirror: Boolean,
        sceneSignals: SceneFrameSignals = SceneFrameSignals(),
    ) {
        // Before anything reads the guide state, apply whatever the main thread
        // asked for. This is the only place any of it is written (#18).
        drainPendingGuideWork()

        val startNs = System.nanoTime()
        val features = featureCalculator.calculate(
            FrameFeatureInput(
                detection = detection,
                tilt = tilt,
                brightness = brightness,
                shake = shake,
            ),
        )
        recordFeatureCost((System.nanoTime() - startNs) / 1_000_000.0)

        val target = _styleTarget.value
        if (sceneStartedNs == 0L) sceneStartedNs = System.nanoTime()
        if (detection.objectsFresh) freshObjectFrames++
        val sceneGuide = sceneGuideSessionController.updateScene(
            detection = detection,
            styleTarget = target,
            signals = sceneSignals,
        )
        detector?.setObjectDetectionPaused(sceneGuide.fixedLayout != null)
        updateSceneMetrics(sceneGuide)
        val resolvedTarget = sceneGuide.proposal.target
        val engineState = alignmentEngine.align(
            features = features,
            target = resolvedTarget,
            config = guideConfig,
            observedSubjectBox = sceneGuide.proposal.subjectBox,
        )
        val projection = stabilizer.stabilize(engineState.toProjection())
        val fixedLayout = sceneGuide.fixedLayout

        // Both of these used to be `fixedLayout != null || projection.<x>`, so a
        // latched layout pinned them true for the rest of the session. The stated
        // reason was "the shutter must never wait for every slot to be filled" —
        // but **neither field gates the shutter.** Capture is unconditional (D2),
        // and each of these has exactly one consumer, both of them KPIs:
        //
        //   visible → the session_guides show/hide collector (CameraScreen)
        //   aligned → analysis_json on the capture row (ShutterSnapshot)
        //
        // So the OR term defended against a gate that does not exist while making
        // two measurements incapable of reporting anything but success. The
        // show/hide KPI logged one row per session and never a hidden one; every
        // capture recorded aligned=true.
        //
        // `visible` is now the raw overlay state, which is what that KPI measures.
        //
        // `aligned` is **null while a layout is latched** (owner decision,
        // 2026-07-28). With the fixed-layout gate in CameraOverlay the preset
        // bracket is not on screen, so `projection.aligned` would score the user
        // against a target they cannot see. Null is the schema's own vocabulary
        // for this — DB 스키마 v2.0 §session_guides: "NULL=측정불가".
        val alignedForKpi: Boolean? = if (fixedLayout != null) null else projection.aligned

        _detectionLabel.value = detectionLabelOf(detection) +
            " · layout=${sceneGuide.layoutGuide.level.name.lowercase()}"

        // §3-3: unconditional, unlike the debug block below.
        _lastFrame.value = ShutterFrame(
            features = features,
            target = target,
            aligned = alignedForKpi,
            visible = projection.visible,
            fixedLayout = fixedLayout,
            observedHorizonPosition = sceneGuide.observation.horizonPosition,
        )

        if (collectDebugSignals) {
            _guideDebug.value = GuideDebug(
                features = features,
                aligned = projection.aligned,
                visible = projection.visible,
                // NOTE: this IoU is AlignmentEngine's internal alignment metric,
                // **not** the §4.2 weighted matchScore. Same name, different
                // quantity — only the `matchScore` field below is loggable (§3-3).
                iou = alignmentEngine.metrics().matchScore,
                matchScore = matchScoreCalculator.calculate(
                    features,
                    resolvedTarget,
                    observedHorizonPosition = sceneGuide.observation.horizonPosition
                        ?: resolvedTarget.horizonPosition,
                ),
                fixedLayoutId = fixedLayout?.template?.id,
            )
        }

        _overlay.value = OverlayData(
            // §3-2: the product overlay is bracket + silhouette + horizon only.
            // Face boxes and the centre dot are the §2-5 coordinate-accuracy
            // affordance, so they are collected in debug builds only and drawn
            // behind a second toggle.
            faces = if (collectDebugSignals) detection.faces.map { it.box } else emptyList(),
            personCenter = if (collectDebugSignals) personCenterOf(detection) else null,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            mirror = mirror,
            guide = projection,
            layoutGuide = sceneGuide.layoutGuide,
            layoutState = sceneGuide.layoutState,
        )
    }

    /** Clears per-frame state when the analyzer detaches (background / rebind). */
    fun onAnalyzerDetached() {
        // Called from `onDispose` on the main thread, at which point the analysis
        // executor has been unbound and nothing else is touching this state.
        // Queued work is dropped rather than applied: it describes a scene the
        // camera is no longer pointed at.
        pendingGuideWork.clear()
        detector?.setObjectDetectionPaused(false)
        _overlay.value = null
        _detectionLabel.value = ""
        _guideDebug.value = null
        // The preview stops with the analyzer. Keeping the open window would make
        // the first frame after the rebind close a window spanning the absence.
        previewMeter.reset()
        _previewStats.value = null
        _previewFpsAvailability.value = PreviewFpsAvailability.NOT_ATTACHED
        // Cleared with the rest: a snapshot from before a background/rebind would
        // describe a scene the camera is no longer pointed at.
        _lastFrame.value = null
        alignmentEngine.reset()
        stabilizer.reset()
        sceneGuideSessionController.endSession()
        sceneStartedNs = 0L
        firstFixedNs = null
        freshObjectFrames = 0L
        _sceneGuideMetrics.value = SceneGuideMetrics()
    }

    /**
     * Currently unwired — the layout picker was removed with the top-bar dropdown
     * (remain_plan 부록 C). Kept because the D13 control it serves is expected back.
     *
     * Returns whether the id names a real template, decided without touching guide
     * state; the state change itself is deferred like every other main-thread
     * mutation. Callers must not read the return value as "applied".
     */
    fun selectManualLayout(templateId: String): Boolean {
        if (LayoutTemplateCatalog.resolve(templateId) == null) return false
        val target = _styleTarget.value
        pendingGuideWork.add { sceneGuideSessionController.selectManualLayout(templateId, target) }
        return true
    }

    /**
     * 재탐색 — drops the latched layout so the next frames search the scene again.
     *
     * The resolver confirms a template within a few frames and then short-circuits
     * for the rest of the session. That stickiness is deliberate — a guide that
     * re-picks every second is worse than one that commits — but until this
     * existed the only way out was [setStyleTarget], so a user who pointed the
     * camera somewhere new had to change their style to make the guide look again.
     * On device that reads as the app having stopped paying attention.
     *
     * Deliberately narrower than [setStyleTarget]: the alignment engine, the
     * stabilizer and the style target are left alone. "Look at the scene again"
     * is not "forget which preset I picked". The scene KPI counters restart with
     * the search, because the two-second first-layout target is measured from
     * when searching begins.
     */
    fun rescanLayout() {
        detector?.setObjectDetectionPaused(false)
        // Deferred to the analysis thread — the counters below are read and written
        // per frame. See [pendingGuideWork].
        pendingGuideWork.add {
            sceneGuideSessionController.rescan()
            firstFixedNs = null
            sceneStartedNs = System.nanoTime()
            freshObjectFrames = 0L
            _sceneGuideMetrics.value = SceneGuideMetrics()
        }
    }

    /**
     * Valid camera taps restart automatic search around their normalized preview
     * point. Like every other UI-originated guide mutation, this must run on the
     * analysis thread; calling the controller directly here would reintroduce the
     * stabilizer/tracker race that [rescanLayout] already avoids.
     */
    fun rescanLayoutAt(anchorX: Float, anchorY: Float) {
        detector?.setObjectDetectionPaused(false)
        pendingGuideWork.add {
            alignmentEngine.reset()
            stabilizer.reset()
            sceneGuideSessionController.rescanAt(anchorX, anchorY)
            firstFixedNs = null
            sceneStartedNs = System.nanoTime()
            freshObjectFrames = 0L
            _sceneGuideMetrics.value = SceneGuideMetrics()
        }
    }

    private fun updateSceneMetrics(sceneGuide: com.gamdo.app.guide.SceneGuideState) {
        val now = System.nanoTime()
        val started = sceneStartedNs.takeIf { it > 0L } ?: now
        val elapsedSeconds = ((now - started) / 1_000_000_000.0).coerceAtLeast(0.001)
        val fixed = sceneGuide.layoutState as? GuideLayoutState.Fixed
        if (fixed != null && firstFixedNs == null) {
            firstFixedNs = now
            if (collectDebugSignals) {
                logSink(
                    "SceneGuide fixed template=${fixed.template.id} source=${fixed.source} " +
                        "firstMs=%.1f signature=%s".format(
                            (now - started) / 1_000_000.0,
                            sceneGuide.sceneSignature,
                        ),
                )
            }
        }
        _sceneGuideMetrics.value = SceneGuideMetrics(
            freshObjectFrames = freshObjectFrames,
            objectFps = freshObjectFrames / elapsedSeconds,
            firstLayoutMs = firstFixedNs?.let { (it - started) / 1_000_000.0 },
            selectedTemplateId = fixed?.template?.id,
            signature = sceneGuide.sceneSignature?.toString(),
        )
    }

    /**
     * §2-4 stopwatch. Emits one line every `features.budgetLogEveryFrames` frames
     * (≈5s at 12fps) and one immediately on the first breach, so a device run
     * produces evidence without spamming logcat at 12Hz.
     */
    private fun recordFeatureCost(elapsedMs: Double) {
        budgetFrames++
        budgetSumMs += elapsedMs
        if (elapsedMs > budgetMaxMs) budgetMaxMs = elapsedMs
        val firstBreach = elapsedMs > budgetMs && budgetOverFrames == 0L
        if (elapsedMs > budgetMs) budgetOverFrames++

        val stats = FeatureBudgetStats(
            frames = budgetFrames,
            lastMs = elapsedMs,
            meanMs = budgetSumMs / budgetFrames,
            maxMs = budgetMaxMs,
            budgetMs = budgetMs,
            overBudgetFrames = budgetOverFrames,
        )
        _featureBudget.value = stats

        val periodic = logEveryFrames > 0 && budgetFrames % logEveryFrames == 0L
        if (firstBreach || periodic) {
            logSink(
                "FrameFeatures n=%d last=%.2fms mean=%.2fms max=%.2fms budget=%.0fms over=%d".format(
                    stats.frames, stats.lastMs, stats.meanMs, stats.maxMs,
                    stats.budgetMs, stats.overBudgetFrames,
                ),
            )
        }
    }

    private fun detectionLabelOf(detection: DetectionResult): String {
        val faces = detection.faces.size
        val pose = detection.pose?.landmarks?.size ?: 0
        val objects = detection.objects.size
        val objectDetails = detection.objects
            .take(2)
            .joinToString(",") { objectObservation ->
                val label = objectObservation.labels.firstOrNull() ?: "unknown"
                val confidence = objectObservation.classificationConfidence
                    ?.let { "%.2f".format(it) } ?: "-"
                "$label:$confidence"
            }
            .ifBlank { "none" }
        val segmentation = if (detection.segmentation != null) "on" else "off"
        return "얼굴 $faces · 포즈 $pose · 물체 $objects[$objectDetails] · seg=$segmentation"
    }

    /**
     * Person centre from pose landmarks above the in-frame likelihood floor,
     * falling back to the primary face box.
     */
    private fun personCenterOf(detection: DetectionResult): Pair<Float, Float>? {
        val landmarks = detection.pose?.landmarks
            ?.filter { it.inFrameLikelihood > config.features.minLandmarkLikelihood }
            ?.takeIf { it.isNotEmpty() }
        if (landmarks != null) {
            return landmarks.map { it.x }.average().toFloat() to
                landmarks.map { it.y }.average().toFloat()
        }
        return detection.faces.firstOrNull()?.box?.let { it.centerX to it.centerY }
    }
}
