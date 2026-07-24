package com.gamdo.app.guide

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class GuideConfigJson(
    val version: Int = 1,
    val smoothingWindow: Int = 5,
    val alignedIouThreshold: Float = 0.7f,
    val recomputeMovementThreshold: Float = 0.08f,
    val minPoseConfidence: Float = 0.3f,
    val maxUnstableFrames: Int = 5,
) {
    fun toGuideConfig(): GuideConfig = GuideConfig(
        smoothingWindow = smoothingWindow,
        alignedIouThreshold = alignedIouThreshold,
        recomputeMovementThreshold = recomputeMovementThreshold,
        minPoseConfidence = minPoseConfidence,
        maxUnstableFrames = maxUnstableFrames,
    )
}

private val guideJson = Json { ignoreUnknownKeys = true }

fun parseGuideConfig(raw: String): GuideConfig =
    guideJson.decodeFromString<GuideConfigJson>(raw).toGuideConfig()
