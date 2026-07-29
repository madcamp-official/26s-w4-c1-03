package com.gamdo.app.camera.gl

/**
 * The part of the preview surface the user can actually see, and therefore the
 * frame 비네팅 has to be measured against.
 *
 * ## Why this is not just "the surface"
 *
 * The preview stream is forced to 4:3 so overlay boxes computed from
 * analysis-normalised coordinates line up (`CameraController`), while D9 offers the
 * user 4:5 and 1:1. `PreviewView` runs `FILL_CENTER`, so the 4:3 surface is scaled
 * to *cover* the pane and the rest is off-screen — and the saved file is cropped to
 * the same window, because CameraX attaches the `PreviewView`'s viewport as the
 * capture's `cropRect`.
 *
 * So a vignette drawn over the whole 4:3 surface would put its corners where the
 * user cannot see them and where the file does not have them. In 4:5 the visible
 * region is the central **60%** of the surface's width; the difference between
 * getting this right and ignoring it is the entire left and right thirds of the
 * darkening.
 *
 * Everything else in the shader is per-pixel colour and does not care.
 */
data class PreviewCrop(val widthPx: Float, val heightPx: Float, val halfU: Float, val halfV: Float) {

    companion object {
        /**
         * The largest centred rect of aspect [ratioWtoH] that fits inside
         * [surfaceWidth] × [surfaceHeight].
         *
         * Returns the crop in pixels (what the vignette's half-diagonal is measured
         * from) and as a half-extent in `[0, 1]` surface coordinates (what the
         * shader maps a fragment through). Degenerate inputs fall back to the whole
         * surface rather than dividing by zero: a wrong vignette is a cosmetic
         * defect, a NaN is a black preview.
         */
        fun fit(surfaceWidth: Int, surfaceHeight: Int, ratioWtoH: Float): PreviewCrop {
            val w = surfaceWidth.toFloat()
            val h = surfaceHeight.toFloat()
            if (w <= 0f || h <= 0f || ratioWtoH <= 0f || !ratioWtoH.isFinite()) {
                return PreviewCrop(w.coerceAtLeast(1f), h.coerceAtLeast(1f), 0.5f, 0.5f)
            }
            val cropWidth: Float
            val cropHeight: Float
            if (w / h > ratioWtoH) {
                // Surface is wider than the target: height is the binding constraint.
                cropHeight = h
                cropWidth = h * ratioWtoH
            } else {
                cropWidth = w
                cropHeight = w / ratioWtoH
            }
            return PreviewCrop(
                widthPx = cropWidth,
                heightPx = cropHeight,
                halfU = (cropWidth / w) * 0.5f,
                halfV = (cropHeight / h) * 0.5f,
            )
        }
    }
}
