package com.gamdo.app.ui.result

/**
 * Where a `ContentScale.Fit` image actually lands inside its container.
 *
 * Coordinates are container pixels, origin top-left.
 */
data class FitRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height

    /** True when the point is on the image rather than on a letterbox bar. */
    fun contains(x: Float, y: Float): Boolean =
        x >= left && x <= right && y >= top && y <= bottom

    /** Container pixels → 0..1 of the *image*, clamped to it. */
    fun normalizeX(x: Float): Float = ((x - left) / width).coerceIn(0f, 1f)

    fun normalizeY(y: Float): Float = ((y - top) / height).coerceIn(0f, 1f)

    /** 0..1 of the image → container pixels. The inverse of [normalizeX]. */
    fun toContainerX(u: Float): Float = left + u * width

    fun toContainerY(v: Float): Float = top + v * height
}

/**
 * The rect a `ContentScale.Fit` bitmap occupies in a container of the given size.
 *
 * `Fit` scales by the smaller of the two ratios and centres the result, so unless
 * the aspects match exactly there are letterbox bars — and **the container is not
 * the image**. Normalizing a touch against the container instead of this rect is
 * a silent error: the number stays in 0..1 and nothing throws, it just points
 * somewhere else. For the erase mask that means the region sent to the server is
 * not the region the user drew, scaled by container/image on each axis.
 *
 * Returns null when either size is unusable, so callers ignore the gesture rather
 * than divide by zero.
 */
fun fitImageRect(containerW: Float, containerH: Float, imageW: Int, imageH: Int): FitRect? {
    if (!containerW.isFinite() || !containerH.isFinite()) return null
    if (containerW <= 0f || containerH <= 0f || imageW <= 0 || imageH <= 0) return null
    val scale = minOf(containerW / imageW, containerH / imageH)
    val w = imageW * scale
    val h = imageH * scale
    return FitRect(
        left = (containerW - w) / 2f,
        top = (containerH - h) / 2f,
        width = w,
        height = h,
    )
}
