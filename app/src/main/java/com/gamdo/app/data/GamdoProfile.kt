package com.gamdo.app.data

import com.gamdo.app.data.preset.ColorParams
import kotlinx.serialization.Serializable
import kotlin.math.abs

/**
 * Versioned, on-device preference policy shared by capture, local editing and
 * rescue. It is deliberately stored in the existing style_profile JSON payload
 * rather than adding a Room table.
 */
@Serializable
data class GamdoProfileV2(
    val version: Int = 2,
    val global: GamdoPolicy,
    val contexts: Map<SceneContext, GamdoPolicy> = emptyMap(),
    val updatedAt: Long,
) {
    fun policyFor(context: SceneContext): GamdoPolicy = contexts[context] ?: global
}

@Serializable
enum class SceneContext {
    GENERAL,
    NIGHT_PERSON,
    PORTRAIT_CLOSEUP,
    OUTDOOR_FULL_BODY,
    CAFE_FOOD,
    GROUP,
    LANDSCAPE,
}

@Serializable
data class CapturePreference(
    val preferredZoom: Float = 1f,
    val subjectScale: Float = 0.5f,
    val anchorX: Float = 0.5f,
    val anchorY: Float = 0.5f,
    val cameraHeight: CameraHeight = CameraHeight.EYE,
    val flash: FlashPreference = FlashPreference.AUTO,
    val backgroundRatio: Float = 0.4f,
    val tiltPreference: Float = 0f,
    val poseMood: PoseMood = PoseMood.NATURAL,
)

@Serializable enum class CameraHeight { LOW, EYE, HIGH }
@Serializable enum class FlashPreference { ON, OFF, AUTO }
@Serializable enum class PoseMood { NATURAL, CENTERED, CANDID }

@Serializable
data class PreferenceEvidence(
    val source: EvidenceSource,
    val sampleCount: Int,
    val note: String = "",
)

@Serializable enum class EvidenceSource { CARD, IMAGE_ANALYSIS, EXIF, BEHAVIOR }

@Serializable
data class GamdoPolicy(
    val capture: CapturePreference = CapturePreference(),
    val color: ColorParams,
    val evidence: List<PreferenceEvidence> = emptyList(),
    val confidence: Float = 0f,
)

/** Small deterministic context classifier; uncertain scenes safely use GENERAL. */
object SceneContextResolver {
    fun resolve(
        personCount: Int,
        objectLabels: Set<String>,
        brightness: Float,
        subjectScale: Float,
    ): SceneContext = when {
        personCount >= 2 -> SceneContext.GROUP
        personCount == 1 && brightness < 0.33f -> SceneContext.NIGHT_PERSON
        personCount == 1 && subjectScale >= 0.55f -> SceneContext.PORTRAIT_CLOSEUP
        personCount == 1 && subjectScale <= 0.34f -> SceneContext.OUTDOOR_FULL_BODY
        objectLabels.any { it in FOOD_LABELS } -> SceneContext.CAFE_FOOD
        personCount == 0 && objectLabels.isEmpty() -> SceneContext.LANDSCAPE
        else -> SceneContext.GENERAL
    }

    private val FOOD_LABELS = setOf("food", "cake", "cup", "bottle", "bowl", "plate")
}

/**
 * Produces bounded capture defaults from the existing card feature model. It is
 * intentionally conservative: cards establish the global policy; photo evidence
 * may later add context-specific policies without replacing the user's baseline.
 */
object GamdoProfileFactory {
    fun fromInitial(result: StyleProfileResult, selectedCardCount: Int, now: Long): GamdoProfileV2 {
        val composition = result.composition
        val color = result.color
        val global = GamdoPolicy(
            capture = CapturePreference(
                preferredZoom = if ((composition["backgroundRatio"]?.mean ?: 0.4f) < 0.38f) 2f else 1f,
                subjectScale = composition["subjectScale"]?.mean ?: 0.5f,
                anchorX = composition["subjectPosition"]?.mean ?: 0.5f,
                backgroundRatio = composition["backgroundRatio"]?.mean ?: 0.4f,
                tiltPreference = ((color["candidness"]?.mean ?: 0.5f) - 0.5f) * 8f,
                poseMood = if ((color["candidness"]?.mean ?: 0.5f) > 0.6f) PoseMood.CANDID else PoseMood.NATURAL,
            ),
            color = ColorParams(
                colorTemperature = color["colorTemperature"]?.mean?.toDouble() ?: 5000.0,
                exposureBias = ((color["brightness"]?.mean ?: 0.5f) - 0.5f).toDouble(),
                contrast = color["contrast"]?.mean?.toDouble() ?: 0.5,
                saturation = color["saturation"]?.mean?.toDouble() ?: 0.5,
                fade = 0.0,
                grain = color["grain"]?.mean?.toDouble() ?: 0.0,
                vignette = 0.0,
                blurStrength = 0.0,
            ),
            evidence = listOf(PreferenceEvidence(EvidenceSource.CARD, selectedCardCount, "initial_cards")),
            confidence = selectedCardCount.coerceAtMost(20) / 20f,
        )
        return GamdoProfileV2(global = global, updatedAt = now)
    }

    fun mergeEvidence(
        current: GamdoProfileV2,
        context: SceneContext,
        candidate: GamdoPolicy,
        weight: Float,
        now: Long,
    ): GamdoProfileV2 {
        val old = current.policyFor(context)
        val w = weight.coerceIn(0f, 0.35f)
        fun blend(a: Double, b: Double) = a + (b - a) * w
        val blended = old.copy(
            capture = old.capture.copy(
                preferredZoom = (old.capture.preferredZoom + (candidate.capture.preferredZoom - old.capture.preferredZoom) * w).coerceIn(0.7f, 2f),
                subjectScale = (old.capture.subjectScale + (candidate.capture.subjectScale - old.capture.subjectScale) * w).coerceIn(0.2f, 0.8f),
                anchorX = (old.capture.anchorX + (candidate.capture.anchorX - old.capture.anchorX) * w).coerceIn(0.1f, 0.9f),
                backgroundRatio = (old.capture.backgroundRatio + (candidate.capture.backgroundRatio - old.capture.backgroundRatio) * w).coerceIn(0.05f, 0.8f),
            ),
            color = old.color.copy(
                colorTemperature = blend(old.color.colorTemperature, candidate.color.colorTemperature),
                exposureBias = blend(old.color.exposureBias, candidate.color.exposureBias),
                contrast = blend(old.color.contrast, candidate.color.contrast),
                saturation = blend(old.color.saturation, candidate.color.saturation),
            ),
            evidence = (old.evidence + candidate.evidence).takeLast(12),
            confidence = (old.confidence + abs(candidate.confidence - old.confidence) * w).coerceIn(0f, 1f),
        )
        return current.copy(contexts = current.contexts + (context to blended), updatedAt = now)
    }
}
