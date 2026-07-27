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

    private fun summary(composition: Map<String, ProfileDimension>, color: Map<String, ProfileDimension>): String {
        val light = if ((color["brightness"]?.mean ?: 0.5f) >= 0.5f) "밝은 자연광" else "차분한 빛"
        val framing = if ((composition["backgroundRatio"]?.mean ?: 0.5f) >= 0.5f) "넓은 배경" else "피사체 중심"
        return "$light, $framing, 자연스러운 인물 배치"
    }
}

// StylePreset.toPresetProfile() lives in PresetProfileMapper.kt — the copy carrying
// the Kelvin-normalisation canary test. An identical extension was declared here too
// after the merge; only a clean build reported it.
