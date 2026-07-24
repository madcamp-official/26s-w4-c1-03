package com.gamdo.app.guide

/**
 * Rendering-neutral projection for the Android camera adapter. The UI layer can
 * map these normalized values to its Canvas without exposing matchScore or copy.
 */
data class OverlayProjection(
    val targetFrame: RectN,
    val silhouetteBounds: RectN?,
    val horizonY: Float,
    val visible: Boolean,
    val aligned: Boolean,
)

fun OverlayState.toProjection(): OverlayProjection = OverlayProjection(
    targetFrame = targetFrame,
    silhouetteBounds = silhouette?.bounds,
    horizonY = horizonY,
    visible = visible,
    aligned = aligned,
)
