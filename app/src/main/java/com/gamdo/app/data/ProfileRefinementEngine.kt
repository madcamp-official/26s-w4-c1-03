package com.gamdo.app.data

import com.gamdo.app.data.preset.ColorParams
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** Turns cached reference analyses into bounded, explainable V2 profile evidence. */
object ProfileRefinementEngine {
    fun refine(profile: GamdoProfileV2, resolutions: List<ReferenceResolution>, now: Long): GamdoProfileV2 {
        return resolutions.fold(profile) { current, resolution ->
            val context = contextOf(resolution.analysis)
            GamdoProfileFactory.mergeEvidence(current, context, policyOf(resolution, current.global), 0.18f, now)
        }
    }

    private fun contextOf(analysis: JsonObject): SceneContext {
        val people = analysis["peopleCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val labels = analysis["subjects"]?.jsonArray.orEmpty().mapNotNull { subject ->
            subject.jsonObjectOrNull()?.get("label")?.jsonPrimitive?.content
        }.toSet()
        val brightness = analysis["brightness"]?.jsonPrimitive?.doubleOrNull?.toFloat() ?: 0.5f
        val subjectScale = analysis["subjectScale"]?.jsonPrimitive?.doubleOrNull?.toFloat() ?: 0.5f
        return SceneContextResolver.resolve(people, labels, brightness, subjectScale)
    }

    private fun policyOf(resolution: ReferenceResolution, fallback: GamdoPolicy): GamdoPolicy {
        val color = resolution.colorTarget
        val target = resolution.targetComposition
        val scale = target["subjectScaleRange"]?.jsonArray?.mapNotNull { it.jsonPrimitive.doubleOrNull }?.average()
            ?.toFloat() ?: fallback.capture.subjectScale
        val background = target["backgroundRatio"]?.jsonArray?.mapNotNull { it.jsonPrimitive.doubleOrNull }?.average()
            ?.toFloat() ?: fallback.capture.backgroundRatio
        return GamdoPolicy(
            capture = fallback.capture.copy(
                preferredZoom = if (background < 0.38f) 2f else 1f,
                subjectScale = scale.coerceIn(0.2f, 0.8f),
                backgroundRatio = background.coerceIn(0.05f, 0.8f),
            ),
            color = ColorParams(
                colorTemperature = color.number("colorTemperature", fallback.color.colorTemperature),
                exposureBias = color.number("exposureBias", fallback.color.exposureBias),
                contrast = color.number("contrast", fallback.color.contrast),
                saturation = color.number("saturation", fallback.color.saturation),
                grain = color.number("grain", fallback.color.grain),
                vignette = color.number("vignette", fallback.color.vignette),
                blurStrength = color.number("blurStrength", fallback.color.blurStrength),
                fade = color.number("fade", fallback.color.fade),
            ),
            evidence = listOf(PreferenceEvidence(EvidenceSource.IMAGE_ANALYSIS, 1, resolution.contentHash.take(12))),
            confidence = 0.65f,
        )
    }

    private fun JsonObject.number(key: String, fallback: Double): Double =
        this[key]?.jsonPrimitive?.doubleOrNull?.takeIf(Double::isFinite) ?: fallback

    private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull(): JsonObject? =
        runCatching { this as JsonObject }.getOrNull()
}
