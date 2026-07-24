package com.gamdo.app.data.preset

import kotlinx.serialization.Serializable

/**
 * StylePreset — the preset contract from 기능명세서 M3-01 (= DB schema §5.3).
 * Same shape as the server's `GET /presets` array items and the bundled
 * `assets/presets.json` fallback. composition (guide) and color (correction)
 * stay separate by design.
 */
@Serializable
data class StylePreset(
    val id: String,
    val name: String,
    val displayName: String,
    val thumbnail: String? = null,
    val composition: Composition,
    val color: ColorParams,
    val cropFreedom: Double = 0.2,
    val generativeEditPolicy: String = "conservative",
)

@Serializable
data class Composition(
    val targetAspectRatio: String,
    val subjectScaleRange: List<Double>,
    val subjectPosition: String,
    val headroomRange: List<Double>,
    val horizonPosition: Double,
    val cameraPitchRange: List<Double>,
    val posePattern: String,
    val backgroundRatio: List<Double>,
)

@Serializable
data class ColorParams(
    val colorTemperature: Double,
    val exposureBias: Double,
    val contrast: Double,
    val saturation: Double,
    val grain: Double,
    val vignette: Double,
    val blurStrength: Double,
    val fade: Double,
)
