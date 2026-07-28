package com.gamdo.app.detect

import kotlin.math.pow

/** The part of the preview in which the user is composing the intended scene. */
data class SceneInterestRegion(
    val centerX: Float,
    val centerY: Float,
    val radiusX: Float,
    val radiusY: Float,
    val source: Source = Source.DEFAULT,
) {
    enum class Source { DEFAULT, TAP }

    init {
        require(centerX in 0f..1f)
        require(centerY in 0f..1f)
        require(radiusX in 0.10f..0.50f)
        require(radiusY in 0.10f..0.50f)
    }

    fun contains(box: NormalizedBox): Boolean =
        (((box.centerX - centerX) / radiusX).toDouble().pow(2.0) +
            ((box.centerY - centerY) / radiusY).toDouble().pow(2.0)) <= 1.0

    companion object {
        val Default = SceneInterestRegion(0.50f, 0.58f, 0.34f, 0.30f)

        fun aroundTap(x: Float, y: Float): SceneInterestRegion = SceneInterestRegion(
            centerX = x.coerceIn(0.16f, 0.84f),
            centerY = y.coerceIn(0.18f, 0.82f),
            radiusX = 0.32f,
            radiusY = 0.28f,
            source = Source.TAP,
        )
    }
}
