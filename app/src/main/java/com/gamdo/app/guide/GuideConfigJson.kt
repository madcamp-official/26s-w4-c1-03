package com.gamdo.app.guide

import com.gamdo.app.detect.DiagnoserConfig
import com.gamdo.app.detect.FrameFeatureCalculator
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * Parser for `assets/guide_config.json` — the single source of every guide
 * tuning threshold (CFG-1). The values in this file are **fallbacks only**: the
 * asset always wins, and a missing or corrupt asset degrades to these defaults
 * instead of crashing.
 *
 * Top-level namespaces (CFG-1):
 * - `alignment` — [AlignmentEngine] thresholds plus the UI-side [OverlayStabilizer]
 * - `features`  — [FrameFeatureCalculator] thresholds and the §2-4 time budget
 * - `diagnoser` — [DiagnoserConfig]; the local-edit vertical owns the value spec
 * - `scoring`   — reserved, see [ScoringConfigJson]
 * - `stability` — pass/fail criteria for the §0.4 overlay-stability harness
 *
 * The parser runs with `ignoreUnknownKeys = true`, so adding a key is always
 * backward compatible in both directions: a new build reads an old asset, and an
 * old build ignores keys it does not know.
 */
@Serializable
data class GuideConfigBundle(
    val version: Int = 2,
    val alignment: AlignmentConfigJson = AlignmentConfigJson(),
    val features: FeaturesConfigJson = FeaturesConfigJson(),
    val diagnoser: DiagnoserConfigJson = DiagnoserConfigJson(),
    val scoring: ScoringConfigJson = ScoringConfigJson(),
    val stability: StabilityConfigJson = StabilityConfigJson(),
) {
    fun toGuideConfig(): GuideConfig = alignment.toGuideConfig()

    fun toStabilizerConfig(): OverlayStabilizerConfig = alignment.overlayStabilizer.toConfig()

    fun toFrameFeatureCalculator(): FrameFeatureCalculator = features.toCalculator()

    fun toDiagnoserConfig(): DiagnoserConfig = diagnoser.toConfig()
}

/**
 * `alignment` — split in two on purpose.
 *
 * The flat keys are [AlignmentEngine]'s own [GuideConfig] (a P2/담당 B module:
 * call-site only, no logic edits). [overlayStabilizer] is the UI-side layer this
 * vertical owns, which post-processes the engine's output for display.
 */
@Serializable
data class AlignmentConfigJson(
    val smoothingWindow: Int = 5,
    val alignedIouThreshold: Float = 0.7f,
    val recomputeMovementThreshold: Float = 0.08f,
    val minPoseConfidence: Float = 0.3f,
    val maxUnstableFrames: Int = 5,
    val overlayStabilizer: OverlayStabilizerConfigJson = OverlayStabilizerConfigJson(),
) {
    fun toGuideConfig(): GuideConfig = GuideConfig(
        smoothingWindow = smoothingWindow,
        alignedIouThreshold = alignedIouThreshold,
        recomputeMovementThreshold = recomputeMovementThreshold,
        minPoseConfidence = minPoseConfidence,
        maxUnstableFrames = maxUnstableFrames,
    )
}

/** `alignment.overlayStabilizer` — see [OverlayStabilizerConfig] for the rationale per value. */
@Serializable
data class OverlayStabilizerConfigJson(
    val alignedEnterFrames: Int = 3,
    val alignedExitFrames: Int = 5,
    val silhouetteHoldFrames: Int = 6,
    val visibleHoldFrames: Int = 6,
    val maxStepPerFrameNorm: Float = 0.01f,
) {
    fun toConfig(): OverlayStabilizerConfig = OverlayStabilizerConfig(
        alignedEnterFrames = alignedEnterFrames,
        alignedExitFrames = alignedExitFrames,
        silhouetteHoldFrames = silhouetteHoldFrames,
        visibleHoldFrames = visibleHoldFrames,
        maxStepPerFrameNorm = maxStepPerFrameNorm,
    )
}

/**
 * `features` — [FrameFeatureCalculator] constructor thresholds plus the §2-4
 * per-frame time budget. The calculator itself is a 담당 B module; injecting its
 * constructor arguments is a call-site change, not a logic change.
 */
@Serializable
data class FeaturesConfigJson(
    val minLandmarkLikelihood: Float = 0.3f,
    val lowLightThreshold: Float = 0.18f,
    val backlightRatioThreshold: Float = 1.8f,
    /** §2-4 budget. Exceeding it is reported, never enforced — dropping features silently would be worse. */
    val analysisBudgetMs: Double = 30.0,
    /** How often the measured cost is emitted. 0 disables the log entirely. */
    val budgetLogEveryFrames: Int = 60,
) {
    fun toCalculator(): FrameFeatureCalculator = FrameFeatureCalculator(
        minLandmarkLikelihood = minLandmarkLikelihood,
        lowLightThreshold = lowLightThreshold,
        backlightRatioThreshold = backlightRatioThreshold,
    )
}

/**
 * `diagnoser` — mirrors [DiagnoserConfig]. The block is reserved and currently
 * ships empty (`{}`), so every value resolves to the module default; the
 * local-edit vertical owns the value spec and this parser adopts it when sent.
 */
@Serializable
data class DiagnoserConfigJson(
    val tiltDegrees: Float = 3f,
    val severeTiltDegrees: Float = 8f,
    val lowBrightness: Float = 0.18f,
    val severeLowBrightness: Float = 0.08f,
    val highBrightness: Float = 0.86f,
    val severeHighBrightness: Float = 0.96f,
    val shadowClipRatio: Float = 0.28f,
    val highlightClipRatio: Float = 0.28f,
    val blurVariance: Float = 80f,
    val severeBlurVariance: Float = 25f,
    val excessMargin: Float = 0.62f,
    val severeExcessMargin: Float = 0.8f,
    val backlightRatio: Float = 1.8f,
) {
    fun toConfig(): DiagnoserConfig = DiagnoserConfig(
        tiltDegrees = tiltDegrees,
        severeTiltDegrees = severeTiltDegrees,
        lowBrightness = lowBrightness,
        severeLowBrightness = severeLowBrightness,
        highBrightness = highBrightness,
        severeHighBrightness = severeHighBrightness,
        shadowClipRatio = shadowClipRatio,
        highlightClipRatio = highlightClipRatio,
        blurVariance = blurVariance,
        severeBlurVariance = severeBlurVariance,
        excessMargin = excessMargin,
        severeExcessMargin = severeExcessMargin,
        backlightRatio = backlightRatio,
    )
}

/**
 * `scoring` — **reserved namespace, not yet injected.**
 *
 * [MatchScoreCalculator] is a 담당 B module with the §4.2 weights compiled in and
 * no constructor seam, so wiring these through would be a logic edit to a frozen
 * file (lead approval required). The namespace exists now so the asset schema is
 * stable before §3-3 starts logging the score in wave 3.
 */
@Serializable
class ScoringConfigJson

/**
 * `stability` — pass/fail criteria for the §0.4 overlay-stability harness. Kept
 * in the asset rather than in test code so the go/no-go bar is tunable in one
 * place and auditable without reading Kotlin (CFG-1).
 */
@Serializable
data class StabilityConfigJson(
    /** Analysis rate the harness simulates; matches `FrameAnalyzer(targetFps)`. */
    val sequenceFps: Int = 12,
    /** §3-2 completion criterion observes for one minute. */
    val sequenceSeconds: Int = 60,
    /**
     * A state change that reverts within this many frames counts as flicker
     * rather than as a real change.
     */
    val minStableFrames: Int = 6,
    /** Max per-frame movement of any overlay rect edge, in normalized units. */
    val maxFrameDeltaNorm: Float = 0.01f,
    /** Anti-cheat: the overlay must actually be on screen while a person is present. */
    val minVisibleRatio: Float = 0.95f,
) {
    val sequenceFrames: Int get() = sequenceFps * sequenceSeconds
}

private val guideJson = Json { ignoreUnknownKeys = true }

/**
 * Parses the namespaced asset. A v1 asset (tuning keys at the top level) is still
 * accepted: when there is no `alignment` object the root itself is read as one.
 */
fun parseGuideConfigBundle(raw: String): GuideConfigBundle {
    val root = guideJson.parseToJsonElement(raw).jsonObject
    val bundle = guideJson.decodeFromJsonElement<GuideConfigBundle>(root)
    if (root.containsKey("alignment")) return bundle
    return bundle.copy(alignment = guideJson.decodeFromJsonElement(root))
}

/** Convenience for call sites that only need the engine thresholds. */
fun parseGuideConfig(raw: String): GuideConfig = parseGuideConfigBundle(raw).toGuideConfig()
