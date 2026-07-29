package com.gamdo.app.data.rescue

import com.gamdo.app.data.GamdoPolicy
import com.gamdo.app.data.SceneContext
import com.gamdo.app.data.preset.ColorParams

enum class ReinterpretationLevel { MEMORY, GAMDO, REIMAGINE }

/** Snapshot passed once to rescue analysis; no live camera frame is involved. */
data class RescueContext(
    val profileVersion: Int = 1,
    val sceneContext: SceneContext = SceneContext.GENERAL,
    val policy: GamdoPolicy = GamdoPolicy(color = ColorParams(5200.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)),
    val level: ReinterpretationLevel = ReinterpretationLevel.GAMDO,
)
