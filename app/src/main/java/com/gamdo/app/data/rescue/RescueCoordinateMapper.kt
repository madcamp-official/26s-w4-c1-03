package com.gamdo.app.data.rescue

import kotlin.math.max

/** Normalized coordinates for a photo displayed with ContentScale.Fit. */
data class RescueImageRect(val left: Float, val top: Float, val width: Float, val height: Float)

data class RescuePoint(val x: Float, val y: Float)

object RescueCoordinateMapper {
    fun viewToImage(point: RescuePoint, imageRect: RescueImageRect): RescuePoint? {
        if (imageRect.width <= 0f || imageRect.height <= 0f) return null
        if (point.x < imageRect.left || point.y < imageRect.top ||
            point.x > imageRect.left + imageRect.width || point.y > imageRect.top + imageRect.height) return null
        return RescuePoint(
            ((point.x - imageRect.left) / imageRect.width).coerceIn(0f, 1f),
            ((point.y - imageRect.top) / imageRect.height).coerceIn(0f, 1f),
        )
    }

    fun fitRect(viewWidth: Float, viewHeight: Float, imageWidth: Int, imageHeight: Int): RescueImageRect {
        val scale = minOf(viewWidth / max(1, imageWidth), viewHeight / max(1, imageHeight))
        val width = imageWidth * scale
        val height = imageHeight * scale
        return RescueImageRect((viewWidth - width) / 2f, (viewHeight - height) / 2f, width, height)
    }
}
