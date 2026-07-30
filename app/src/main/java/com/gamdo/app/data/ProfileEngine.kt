package com.gamdo.app.data

import com.gamdo.app.data.preset.StylePreset
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.sqrt

/** Pure JVM personalization logic. It has no Android, Room, or network dependency. */
data class CardFeature(
    val id: String,
    val subjectScale: Float,
    val subjectPosition: Float,
    val headroom: Float,
    val backgroundRatio: Float,
    val brightness: Float,
    val lightType: String,
    val colorTemperature: Float,
    val saturation: Float,
    val contrast: Float,
    val sharpness: Float,
    val grain: Float,
    val candidness: Float,
    val framing: Float,
)

@Serializable
data class ProfileDimension(val mean: Float, val confidence: Float)

data class PresetProfile(
    val id: String,
    val composition: Map<String, Float>,
    val color: Map<String, Float>,
)

data class StyleProfileResult(
    val composition: Map<String, ProfileDimension>,
    val color: Map<String, ProfileDimension>,
    val recommendedPresetIds: List<String>,
    val summary: String,
)

enum class FeedbackSignal { PERFECT, COMPOSITION_GOOD_COLOR_BAD, COLOR_GOOD_BUT_ARTIFICIAL, MORE_NATURAL_NEXT }

object ProfileEngine {
    private const val ALPHA = 0.3f
    // Card/preset color temperature values use Kelvin, while the other
    // dimensions are already normalized to 0..1. Keep Kelvin from drowning
    // out composition and tone when ranking recommendations.
    private const val COLOR_TEMPERATURE_SPAN = 2000f
    private val compositionKeys = listOf("subjectScale", "subjectPosition", "headroom", "backgroundRatio", "framing")
    private val colorKeys = listOf("brightness", "colorTemperature", "saturation", "contrast", "sharpness", "grain", "candidness")

    fun build(cards: List<CardFeature>, presets: List<PresetProfile>): StyleProfileResult {
        require(cards.isNotEmpty()) { "at least one card is required" }
        val composition = profileDimensions(cards.map(::compositionValues), compositionKeys)
        val color = profileDimensions(cards.map(::colorValues), colorKeys)
        return StyleProfileResult(composition, color, recommend(composition, color, presets), summary(composition, color))
    }

    fun applyFeedback(profile: StyleProfileResult, signal: FeedbackSignal): StyleProfileResult {
        if (signal == FeedbackSignal.PERFECT) return profile
        val color = profile.color.toMutableMap()
        val composition = profile.composition.toMutableMap()
        when (signal) {
            FeedbackSignal.COMPOSITION_GOOD_COLOR_BAD -> adjust(color, "colorTemperature", -350f)
            FeedbackSignal.COLOR_GOOD_BUT_ARTIFICIAL -> adjust(color, "saturation", -0.08f)
            FeedbackSignal.MORE_NATURAL_NEXT -> {
                adjust(color, "saturation", -0.05f)
                adjust(color, "contrast", -0.05f)
                adjust(composition, "framing", 0.04f)
            }
            FeedbackSignal.PERFECT -> Unit
        }
        return profile.copy(composition = composition, color = color, summary = summary(composition, color))
    }

    private fun compositionValues(card: CardFeature) = mapOf(
        "subjectScale" to card.subjectScale,
        "subjectPosition" to card.subjectPosition,
        "headroom" to card.headroom,
        "backgroundRatio" to card.backgroundRatio,
        "framing" to card.framing,
    )

    private fun colorValues(card: CardFeature) = mapOf(
        "brightness" to card.brightness,
        "colorTemperature" to card.colorTemperature,
        "saturation" to card.saturation,
        "contrast" to card.contrast,
        "sharpness" to card.sharpness,
        "grain" to card.grain,
        "candidness" to card.candidness,
    )

    private fun profileDimensions(values: List<Map<String, Float>>, keys: List<String>): Map<String, ProfileDimension> =
        keys.associateWith { key ->
            val samples = values.map { it.getValue(key) }
            val mean = samples.average().toFloat()
            val variance = samples.sumOf { (it - mean).toDouble() * (it - mean).toDouble() } / samples.size
            ProfileDimension(mean, (1f / (1f + sqrt(variance).toFloat())).coerceIn(0f, 1f))
        }

    private fun recommend(
        composition: Map<String, ProfileDimension>,
        color: Map<String, ProfileDimension>,
        presets: List<PresetProfile>,
    ): List<String> = presets.sortedBy { preset ->
        (composition + color).entries.sumOf { (key, value) ->
            normalizedDistance(
                key,
                value.mean,
                (preset.composition + preset.color)[key] ?: value.mean,
            ).toDouble()
        }
    }.take(3).map { it.id }

    private fun normalizedDistance(key: String, actual: Float, target: Float): Float {
        val distance = abs(actual - target)
        return if (key == "colorTemperature") {
            (distance / COLOR_TEMPERATURE_SPAN).coerceAtMost(1f)
        } else {
            distance.coerceAtMost(1f)
        }
    }

    private fun adjust(values: MutableMap<String, ProfileDimension>, key: String, delta: Float) {
        val current = values[key] ?: return
        values[key] = current.copy(mean = current.mean + ALPHA * delta)
    }

    /**
     * The three lines under 당신의 감도를 저장했어요, split on `", "` by the screen.
     *
     * ## Every line has to move
     *
     * The previous version could produce four summaries in total: two states of
     * brightness crossed with two of background, and then a **literal constant**,
     * `"자연스러운 인물 배치"`, which appeared no matter what the user picked —
     * including for someone who chose five photographs with no person in them.
     * Under a heading that says *your* 감도, a line that is the same for everybody
     * is the same defect `ProfilePalette` was written to remove from the swatches
     * (AGENTS.md §7-6).
     *
     * So each line now reads a different axis, and no axis is read twice: light
     * from brightness × temperature, shape from how much frame the background
     * takes, colour from saturation × contrast. Picking dark cafés and picking
     * bright windows cannot land on the same sentence.
     *
     * These are descriptions, not measurements — the numbers the recommendation
     * runs on are untouched. The thresholds are chosen so the bands are reachable
     * with the bundled deck rather than being evenly spaced: `CardRepositoryTest`
     * pins that the darkest five and the brightest five summarise differently.
     */
    private fun summary(composition: Map<String, ProfileDimension>, color: Map<String, ProfileDimension>): String =
        listOf(
            lightPhrase(color["brightness"]?.mean ?: 0.5f, color["colorTemperature"]?.mean ?: 5500f),
            framingPhrase(composition["backgroundRatio"]?.mean ?: 0.5f),
            colorPhrase(color["saturation"]?.mean ?: 0.5f, color["contrast"]?.mean ?: 0.5f),
        ).joinToString(", ")

    /**
     * Brightness names the phrase, temperature qualifies it.
     *
     * Warm and cool are deliberately asymmetric at the dark end: an unlit room and
     * a night street are both dark, and what separates them for a photographer is
     * the colour of what little light there is.
     */
    private fun lightPhrase(brightness: Float, kelvin: Float): String {
        val warm = kelvin < 4800f
        val cool = kelvin > 6000f
        return when {
            brightness >= 0.50f -> if (warm) "밝고 따뜻한 빛" else if (cool) "밝고 서늘한 빛" else "밝은 자연광"
            brightness >= 0.28f -> if (warm) "차분하고 따뜻한 빛" else if (cool) "차분하고 서늘한 빛" else "차분한 빛"
            else -> if (warm) "어둡고 따뜻한 조명" else if (cool) "어둡고 푸른 밤빛" else "어두운 빛"
        }
    }

    /** How much of the frame is *not* the subject — the one composition axis a viewer names unprompted. */
    private fun framingPhrase(backgroundRatio: Float): String = when {
        backgroundRatio >= 0.62f -> "여백이 넓은 구도"
        backgroundRatio >= 0.40f -> "균형 잡힌 구도"
        else -> "피사체 중심"
    }

    /**
     * Saturation decides the noun, contrast sharpens it.
     *
     * Near-zero saturation gets its own phrase rather than being folded into
     * "차분한": someone who picked black-and-white photographs has said something
     * specific, and calling it "subdued colour" would be describing a different
     * choice back to them.
     */
    private fun colorPhrase(saturation: Float, contrast: Float): String {
        val crisp = contrast >= 0.75f
        return when {
            saturation < 0.15f -> if (crisp) "또렷한 무채색" else "거의 무채색"
            saturation >= 0.45f -> if (crisp) "진하고 또렷한 색" else "진한 색감"
            else -> if (crisp) "또렷한 색감" else "차분한 색감"
        }
    }
}

// StylePreset.toPresetProfile() lives in PresetProfileMapper.kt — the copy carrying
// the Kelvin-normalisation canary test. An identical extension was declared here too
// after the merge; only a clean build reported it.
