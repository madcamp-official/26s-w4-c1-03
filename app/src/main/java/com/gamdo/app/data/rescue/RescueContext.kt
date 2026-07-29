package com.gamdo.app.data.rescue

import com.gamdo.app.data.GamdoPolicy
import com.gamdo.app.data.GamdoProfileV2
import com.gamdo.app.data.SceneContext
import com.gamdo.app.data.SceneContextResolver
import com.gamdo.app.data.preset.ColorParams

enum class ReinterpretationLevel { MEMORY, GAMDO, REIMAGINE }

/** Snapshot passed once to rescue analysis; no live camera frame is involved. */
data class RescueContext(
    val profileVersion: Int = 1,
    val sceneContext: SceneContext = SceneContext.GENERAL,
    val policy: GamdoPolicy = GamdoPolicy(color = ColorParams(5200.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)),
    val level: ReinterpretationLevel = ReinterpretationLevel.GAMDO,
)

/**
 * Single UI-facing conversion seam: result screens pass simple scene signals,
 * never construct server policy JSON or duplicate context precedence rules.
 */
object RescueContextFactory {
    fun fromProfile(
        profile: GamdoProfileV2?,
        personCount: Int,
        objectLabels: Set<String>,
        brightness: Float,
        subjectScale: Float,
        level: ReinterpretationLevel,
    ): RescueContext {
        val scene = SceneContextResolver.resolve(personCount, objectLabels, brightness, subjectScale)
        return fromProfile(profile, scene, level)
    }

    fun fromProfile(
        profile: GamdoProfileV2?,
        scene: SceneContext = SceneContext.GENERAL,
        level: ReinterpretationLevel = ReinterpretationLevel.GAMDO,
    ): RescueContext {
        val fallback = GamdoPolicy(
            color = ColorParams(5200.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
        )
        return RescueContext(
            profileVersion = profile?.version ?: 1,
            sceneContext = scene,
            policy = profile?.policyFor(scene) ?: fallback,
            level = level,
        )
    }
}
