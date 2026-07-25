package com.gamdo.app.ui.camera

import com.gamdo.app.BuildConfig
import com.gamdo.app.camera.AnalysisStats
import com.gamdo.app.camera.TiltReading
import com.gamdo.app.detect.BrightnessSample
import com.gamdo.app.detect.DetectionResult
import com.gamdo.app.detect.FrameFeatureCalculator
import com.gamdo.app.detect.FrameFeatureInput
import com.gamdo.app.detect.FrameFeatures
import com.gamdo.app.guide.AlignmentEngine
import com.gamdo.app.guide.GuideConfig
import com.gamdo.app.guide.MatchScoreCalculator
import com.gamdo.app.guide.StyleTarget
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
 * State holder for the camera screen (P1 §3-1) — **the analysis-thread → UI
 * boundary lives here and nowhere else**.
 *
 * [onFrameAnalyzed] and [onStats] are called from the CameraX analysis executor;
 * every result is published through a [StateFlow] that Compose collects on the
 * main thread. Nothing in this class touches Android, CameraX or ML Kit, so the
 * whole reduction path is JVM-unit-testable: the platform layer (`CameraScreen`)
 * owns `ImageProxy` → `AnalysisFrame` → `DetectionResult` conversion and hands
 * over plain data.
 *
 * Thresholds arrive via [guideConfig], sourced from `assets/guide_config.json`
 * (CFG-1) — code defaults are fallbacks only.
 */
class CameraViewModel(
    private val guideConfig: GuideConfig = GuideConfig(),
    private val featureCalculator: FrameFeatureCalculator = FrameFeatureCalculator(),
    private val alignmentEngine: AlignmentEngine = AlignmentEngine(),
    private val matchScoreCalculator: MatchScoreCalculator = MatchScoreCalculator(),
    /** Debug signal collection; off in release so the guide chain costs nothing. */
    private val collectDebugSignals: Boolean = BuildConfig.DEBUG,
) {

    private val _stats = MutableStateFlow<AnalysisStats?>(null)
    val stats: StateFlow<AnalysisStats?> = _stats.asStateFlow()

    private val _detectionLabel = MutableStateFlow("")
    val detectionLabel: StateFlow<String> = _detectionLabel.asStateFlow()

    private val _overlay = MutableStateFlow<OverlayData?>(null)
    val overlay: StateFlow<OverlayData?> = _overlay.asStateFlow()

    private val _guideDebug = MutableStateFlow<GuideDebug?>(null)
    val guideDebug: StateFlow<GuideDebug?> = _guideDebug.asStateFlow()

    private val _styleTarget = MutableStateFlow(StyleTarget())
    val styleTarget: StateFlow<StyleTarget> = _styleTarget.asStateFlow()

    /**
     * Swaps the composition target. A preset switch invalidates the smoothing
     * window and the last stable target, so the engine is reset with it.
     */
    fun setStyleTarget(target: StyleTarget) {
        _styleTarget.value = target
        alignmentEngine.reset()
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
    ) {
        val features = featureCalculator.calculate(
            FrameFeatureInput(
                detection = detection,
                tilt = tilt,
                brightness = brightness,
                shake = shake,
            ),
        )
        val target = _styleTarget.value
        val projection = alignmentEngine.align(features, target, guideConfig).toProjection()

        _detectionLabel.value = detectionLabelOf(detection)

        if (collectDebugSignals) {
            _guideDebug.value = GuideDebug(
                features = features,
                aligned = projection.aligned,
                visible = projection.visible,
                iou = alignmentEngine.metrics().matchScore,
                matchScore = matchScoreCalculator.calculate(features, target),
            )
        }

        _overlay.value = OverlayData(
            faces = detection.faces.map { it.box },
            personCenter = personCenterOf(detection),
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
    }

    private fun detectionLabelOf(detection: DetectionResult): String {
        val faces = detection.faces.size
        val pose = detection.pose?.landmarks?.size ?: 0
        return "얼굴 $faces · 포즈 $pose"
    }

    /**
     * Person centre from pose landmarks above the in-frame likelihood floor,
     * falling back to the primary face box.
     */
    private fun personCenterOf(detection: DetectionResult): Pair<Float, Float>? {
        val landmarks = detection.pose?.landmarks
            ?.filter { it.inFrameLikelihood > MIN_LANDMARK_LIKELIHOOD }
            ?.takeIf { it.isNotEmpty() }
        if (landmarks != null) {
            return landmarks.map { it.x }.average().toFloat() to
                landmarks.map { it.y }.average().toFloat()
        }
        return detection.faces.firstOrNull()?.box?.let { it.centerX to it.centerY }
    }

    private companion object {
        const val MIN_LANDMARK_LIKELIHOOD = 0.3f
    }
}
