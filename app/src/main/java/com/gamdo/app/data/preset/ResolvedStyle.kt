package com.gamdo.app.data.preset

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * The single style contract consumed by both the camera guide and local editor.
 * A reference is an additional source; it never replaces the six system presets.
 */
data class ResolvedStyle(
    val source: Source,
    val sourceKey: String,
    val displayName: String,
    val composition: Composition,
    val color: ColorParams,
    val referenceScope: ReferenceScope = ReferenceScope.BOTH,
    val strength: Double = DEFAULT_STRENGTH,
    val referenceHash: String? = null,
) {
    enum class Source { PRESET, REFERENCE }
    enum class ReferenceScope { BOTH, COMPOSITION, COLOR }

    fun clamped(): ResolvedStyle = copy(
        strength = strength.coerceIn(0.0, 1.0),
    )

    companion object {
        const val DEFAULT_STRENGTH = 0.7

        fun fromPreset(preset: StylePreset): ResolvedStyle = ResolvedStyle(
            source = Source.PRESET,
            sourceKey = preset.id,
            displayName = preset.displayName,
            composition = preset.composition,
            color = preset.color,
        )

        /** Converts the additive server analysis response into the app style contract. */
        fun fromReference(
            hash: String,
            target: JsonObject,
            colorTarget: JsonObject,
            scope: ReferenceScope = ReferenceScope.BOTH,
            strength: Double = DEFAULT_STRENGTH,
        ): ResolvedStyle {
            val composition = Composition(
                targetAspectRatio = target.string("targetAspectRatio") ?: "4:5",
                subjectScaleRange = target.range("subjectScaleRange", 0.25, 0.75),
                subjectPosition = target.string("subjectPosition") ?: "center",
                headroomRange = target.range("headroomRange", 0.04, 0.24),
                horizonPosition = target.number("horizonPosition", 0.5),
                cameraPitchRange = target.range("cameraPitchRange", -5.0, 5.0),
                posePattern = target.string("posePattern") ?: "natural",
                backgroundRatio = target.range("backgroundRatio", 0.25, 0.85),
            )
            val color = ColorParams(
                colorTemperature = colorTarget.number("colorTemperature", 5200.0),
                exposureBias = colorTarget.number("exposureBias", 0.0),
                contrast = colorTarget.number("contrast", 0.0),
                saturation = colorTarget.number("saturation", 0.0),
                grain = colorTarget.number("grain", 0.0),
                vignette = colorTarget.number("vignette", 0.0),
                blurStrength = colorTarget.number("blurStrength", 0.0),
                fade = colorTarget.number("fade", 0.0),
            )
            return ResolvedStyle(
                source = Source.REFERENCE,
                sourceKey = hash,
                displayName = "내 레퍼런스",
                composition = composition,
                color = color,
                referenceScope = scope,
                strength = strength,
                referenceHash = hash,
            ).clamped()
        }
    }
}

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content

private fun JsonObject.number(key: String, fallback: Double): Double =
    this[key]?.jsonPrimitive?.doubleOrNull?.takeIf { it.isFinite() } ?: fallback

private fun JsonObject.range(key: String, fallbackMin: Double, fallbackMax: Double): List<Double> {
    val values = this[key]?.jsonArray?.mapNotNull { it.jsonPrimitive.doubleOrNull }
    return if (values?.size == 2) {
        listOf(values[0].coerceIn(-100.0, 100.0), values[1].coerceIn(-100.0, 100.0))
    } else {
        listOf(fallbackMin, fallbackMax)
    }
}
