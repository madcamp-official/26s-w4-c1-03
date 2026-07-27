package com.gamdo.app.ui.camera

import com.gamdo.app.BuildConfig
import com.gamdo.app.camera.AnalysisStats
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
    private val guideConfig = config.toGuideConfig()
    private val featureCalculator = config.toFrameFeatureCalculator()
    private val stabilizer = OverlayStabilizer(config.toStabilizerConfig())
    private val sceneGuideCoordinator = SceneGuideCoordinator()
    private val budgetMs = config.features.analysisBudgetMs
    private val logEveryFrames = config.features.budgetLogEveryFrames

    private var budgetFrames = 0L
    private var budgetSumMs = 0.0
    private var budgetMaxMs = 0.0
    private var budgetOverFrames = 0L

    private val _stats = MutableStateFlow<AnalysisStats?>(null)
    val stats: StateFlow<AnalysisStats?> = _stats.asStateFlow()

    private val _detectionLabel = MutableStateFlow("")
    val detectionLabel: StateFlow<String> = _detectionLabel.asStateFlow()

    private val _overlay = MutableStateFlow<OverlayData?>(null)
    val overlay: StateFlow<OverlayData?> = _overlay.asStateFlow()

    private val _guideDebug = MutableStateFlow<GuideDebug?>(null)
    val guideDebug: StateFlow<GuideDebug?> = _guideDebug.asStateFlow()

    private val _featureBudget = MutableStateFlow<FeatureBudgetStats?>(null)
    val featureBudget: StateFlow<FeatureBudgetStats?> = _featureBudget.asStateFlow()

    private val _styleTarget = MutableStateFlow(StyleTarget())
    val styleTarget: StateFlow<StyleTarget> = _styleTarget.asStateFlow()

    /**
     * Swaps the composition target. A preset switch invalidates the smoothing
     * window, the last stable target and the display damping, so both stages are
     * reset with it — otherwise the new bracket would crawl out of the old one.
     */
    fun setStyleTarget(target: StyleTarget) {
        _styleTarget.value = target
        alignmentEngine.reset()
        stabilizer.reset()
    }

    /** Called from the analysis executor once per second. */
    fun onStats(stats: AnalysisStats) {
        _stats.value = stats
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
        val sceneGuide = sceneGuideCoordinator.update(
            detection = detection,
            styleTarget = target,
            signals = sceneSignals,
        )
        val resolvedTarget = sceneGuide.proposal.target
        val engineState = alignmentEngine.align(
            features = features,
            target = resolvedTarget,
            config = guideConfig,
            observedSubjectBox = sceneGuide.proposal.subjectBox,
        )
        val projection = stabilizer.stabilize(engineState.toProjection())

        _detectionLabel.value = detectionLabelOf(detection)

        if (collectDebugSignals) {
            _guideDebug.value = GuideDebug(
                features = features,
                aligned = projection.aligned,
                visible = projection.visible,
                // NOTE: this IoU is AlignmentEngine's internal alignment metric,
                // **not** the §4.2 weighted matchScore. Same name, different
                // quantity — only the `matchScore` field below is loggable (§3-3).
                iou = alignmentEngine.metrics().matchScore,
                matchScore = matchScoreCalculator.calculate(features, resolvedTarget),
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
        )
    }

    /** Clears per-frame state when the analyzer detaches (background / rebind). */
    fun onAnalyzerDetached() {
        _overlay.value = null
        _detectionLabel.value = ""
        _guideDebug.value = null
        alignmentEngine.reset()
        stabilizer.reset()
        sceneGuideCoordinator.reset()
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
        return "얼굴 $faces · 포즈 $pose · 물체 $objects"
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
