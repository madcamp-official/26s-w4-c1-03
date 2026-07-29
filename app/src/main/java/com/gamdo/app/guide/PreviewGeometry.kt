package com.gamdo.app.guide

import kotlin.math.max

data class PointN(val x: Float, val y: Float) {
    fun clamped(): PointN = PointN(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
}

data class InsetsN(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
) {
    init {
        require(left >= 0f && top >= 0f && right >= 0f && bottom >= 0f)
        require(left + right < 1f && top + bottom < 1f)
    }
}

/** Dynamic full-bleed PreviewView geometry shared by guides and selection. */
data class PreviewGeometry(
    val viewWidth: Int,
    val viewHeight: Int,
    val analysisWidth: Int,
    val analysisHeight: Int,
    val mirror: Boolean = false,
    val safeInsets: InsetsN = InsetsN(),
) {
    init {
        require(viewWidth > 0 && viewHeight > 0)
        require(analysisWidth > 0 && analysisHeight > 0)
    }

    /** Maps a point in PreviewView pixels to upright, unmirrored analysis coordinates. */
    fun viewToAnalysis(x: Float, y: Float): PointN? {
        if (x !in 0f..viewWidth.toFloat() || y !in 0f..viewHeight.toFloat()) return null
        val scale = max(viewWidth.toFloat() / analysisWidth, viewHeight.toFloat() / analysisHeight)
        val drawnWidth = analysisWidth * scale
        val drawnHeight = analysisHeight * scale
        val offsetX = (viewWidth - drawnWidth) / 2f
        val offsetY = (viewHeight - drawnHeight) / 2f
        var nx = ((x - offsetX) / drawnWidth).coerceIn(0f, 1f)
        val ny = ((y - offsetY) / drawnHeight).coerceIn(0f, 1f)
        if (mirror) nx = 1f - nx
        return PointN(nx, ny)
    }

    /** Maps upright analysis coordinates onto the full-bleed preview. */
    fun analysisToView(point: PointN): Pair<Float, Float> {
        val scale = max(viewWidth.toFloat() / analysisWidth, viewHeight.toFloat() / analysisHeight)
        val drawnWidth = analysisWidth * scale
        val drawnHeight = analysisHeight * scale
        val offsetX = (viewWidth - drawnWidth) / 2f
        val offsetY = (viewHeight - drawnHeight) / 2f
        val nx = if (mirror) 1f - point.x else point.x
        return offsetX + nx * drawnWidth to offsetY + point.y * drawnHeight
    }

    fun applySafeArea(rect: RectN): RectN {
        val usableWidth = 1f - safeInsets.left - safeInsets.right
        val usableHeight = 1f - safeInsets.top - safeInsets.bottom
        return RectN(
            safeInsets.left + rect.left * usableWidth,
            safeInsets.top + rect.top * usableHeight,
            safeInsets.left + rect.right * usableWidth,
            safeInsets.top + rect.bottom * usableHeight,
        ).clamped()
    }
}

