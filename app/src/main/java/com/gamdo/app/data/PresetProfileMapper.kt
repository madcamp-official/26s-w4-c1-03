package com.gamdo.app.data

import com.gamdo.app.data.preset.StylePreset

/**
 * Adapts [StylePreset] (server / `assets/presets.json` schema) into [PresetProfile],
 * the shape `ProfileEngine.build()` / `ProfileEngine.recommend()` compares
 * card-derived profiles against. This is boundary code, not a change to B's
 * (`ProfileEngine.kt`) distance logic — it only decides what goes into the preset
 * side of the comparison.
 *
 * **Per lead ruling (wave 0 escalation response):** where a preset field has no real
 * counterpart in [CardFeature]'s space, the dimension is **omitted** from the map
 * rather than filled with an invented constant. `ProfileEngine.recommend()` (line
 * ~102) treats a missing key as `abs(mean - mean) == 0` — an omitted dimension
 * contributes zero distance for every preset, equally. That is the honest way to
 * encode "no data" here; a guessed value would instead arbitrarily tilt the ranking,
 * which is worse than contributing nothing.
 *
 * Status per dimension — **MAP** (real correspondence, used as-is or via a trivial
 * range-midpoint), **DERIVE** (a transform with a physically-grounded justification,
 * documented per line below), or **OMIT** (no defensible correspondence found, left
 * out of the map):
 *
 * composition:
 * - `subjectScale`    MAP    — midpoint of `subjectScaleRange`; same 0..1 field on
 *                               both sides.
 * - `subjectPosition` MAP    — `third_left` / `center` / `third_right` → 1/3, 1/2,
 *                               2/3; same axis `CardFeature.subjectPosition` measures.
 * - `headroom`        MAP    — midpoint of `headroomRange`; same 0..1 field.
 * - `backgroundRatio` MAP    — midpoint of `backgroundRatio` range; same field.
 * - `framing`         OMIT   — no counterpart. `cropFreedom` describes how much the
 *                               generative-edit step is allowed to recrop the shot,
 *                               not the *intended* frame tightness `CardFeature.framing`
 *                               captures. The harness's `1f - cropFreedom` guess has
 *                               no grounding and is not carried over.
 *
 * color:
 * - `brightness`       DERIVE — `0.5 + exposureBias`, clamped to 0..1. `exposureBias`
 *                                is a signed EV-ish compensation; a higher exposure
 *                                compensation causally produces a brighter resulting
 *                                image, so a 0.5-centered baseline plus the delta is a
 *                                physically grounded stand-in for the absolute
 *                                brightness `CardFeature.brightness` records.
 * - `colorTemperature` MAP    — same Kelvin field on both sides, no transform.
 * - `saturation`       DERIVE — `0.5 + saturation`, clamped to 0..1. Same reasoning as
 *                                brightness: `assets/presets.json` shows this preset
 *                                field is a small signed adjustment (roughly -0.05..
 *                                0.12 across the bundled 6), and a positive adjustment
 *                                causally increases the resulting image's saturation.
 * - `contrast`         DERIVE — `0.5 + contrast`, clamped to 0..1; identical reasoning
 *                                to saturation.
 * - `sharpness`        DERIVE — `1f - blurStrength`, clamped to 0..1. No sharpness
 *                                field exists on the preset; `blurStrength` is the
 *                                amount of deliberate blur the preset applies, which
 *                                inversely and causally affects resulting sharpness.
 *                                Bundled presets keep `blurStrength` small (0..0.1), so
 *                                this dimension carries little variance between
 *                                presets today — that is a property of the current
 *                                preset data, not a bug in the derivation.
 * - `grain`            MAP    — same 0..1 "amount of grain" field on both sides; used
 *                                as-is, no sign or baseline needed.
 * - `candidness`       OMIT   — no counterpart. `posePattern` is categorical
 *                                (`natural_standing` / `candid_motion` / ...); the
 *                                harness's bucketed guess (0.85 / 0.6 / 0.4) has no
 *                                grounding and is not carried over.
 *
 * Note: the native-Kelvin-vs-0..1-dimensions unit mismatch inside
 * `ProfileEngine.recommend()`'s unweighted distance sum (colorTemperature dominates
 * the ranking) is a known, separate issue routed to B's `ProfileEngine.kt` by the
 * lead. This mapper does not attempt to compensate for it — see
 * `PresetProfileMapperTest` for a regression marker instead of a fix.
 */
fun StylePreset.toPresetProfile(): PresetProfile {
    fun mid(range: List<Double>) = ((range[0] + range[1]) / 2.0).toFloat()
    fun baselinePlusDelta(delta: Double) = (0.5f + delta.toFloat()).coerceIn(0f, 1f)

    return PresetProfile(
        id = id,
        composition = mapOf(
            "subjectScale" to mid(composition.subjectScaleRange),
            "subjectPosition" to when (composition.subjectPosition) {
                "third_left" -> 1f / 3f
                "third_right" -> 2f / 3f
                else -> 0.5f
            },
            "headroom" to mid(composition.headroomRange),
            "backgroundRatio" to mid(composition.backgroundRatio),
            // "framing": omitted, see class KDoc — no defensible counterpart.
        ),
        color = mapOf(
            "brightness" to baselinePlusDelta(color.exposureBias),
            "colorTemperature" to color.colorTemperature.toFloat(),
            "saturation" to baselinePlusDelta(color.saturation),
            "contrast" to baselinePlusDelta(color.contrast),
            "sharpness" to (1f - color.blurStrength.toFloat()).coerceIn(0f, 1f),
            "grain" to color.grain.toFloat(),
            // "candidness": omitted, see class KDoc — no defensible counterpart.
        ),
    )
}

/** Convenience: maps a whole bundle (e.g. `PresetRepository.loadBundledPresets()`). */
fun List<StylePreset>.toPresetProfiles(): List<PresetProfile> = map { it.toPresetProfile() }
