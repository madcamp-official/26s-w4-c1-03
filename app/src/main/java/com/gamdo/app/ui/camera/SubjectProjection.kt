package com.gamdo.app.ui.camera

import com.gamdo.app.detect.NormalizedBox
import com.gamdo.app.edit.SubjectBox

/**
 * Projects a subject box from **analysis-frame** coordinates into **stored-file**
 * coordinates (§3-3 → §4-1).
 *
 * `SubjectBox`'s contract says the coordinates are the stored file's, and names
 * this side as the owner of the conversion. Getting it wrong is not loud: the
 * editor would centre its crop on a plausible-looking but wrong region, and no
 * test downstream could tell.
 *
 * ## Why two crops and not one
 *
 * A capture reaches the file through **two** centre crops, and they do not
 * collapse into one:
 *
 * 1. **viewport** — CameraX attaches the `PreviewView`'s viewport as the capture's
 *    `cropRect`, so the saved pixels are what the preview showed (WYSIWYG). That
 *    crops the sensor's 4:3 down to the *preview pane's* aspect.
 * 2. **aspect** — `centerCropToRatio` then crops that to 4:5 or 1:1 (D9).
 *
 * Composing 4:3 → 0.72 → 0.8 keeps 96% of the width and 90% of the height, while a
 * direct 4:3 → 0.8 keeps 100% and 93.75%. Different regions. The pane's aspect is
 * a real term in the projection, not an intermediate that cancels — which is why
 * [project] takes it.
 *
 * The model is arithmetic, not assumption, and the device confirms it: on
 * SM-G970N the sensor emits 3024×4032, the pane is 1080×1500, and the saved file
 * measured **2904×3630**. Predicted: 4032 × (1080/1500) = 2903.04 → 2904 wide,
 * then 2904 / 0.8 = 3630 tall. Exact.
 *
 * ## Mirroring
 *
 * The front lens is mirrored into the saved pixels to match the preview, but the
 * detector sees the unmirrored frame. A horizontal flip commutes with centre
 * crops (both are symmetric about the centre), so [project] applies it last.
 */
object SubjectProjection {

    /**
     * The analysis and preview streams are both pinned to 4:3 by
     * `CameraController`, so this is the aspect the normalized detector
     * coordinates are expressed in. Portrait: width / height = 3/4.
     */
    const val ANALYSIS_RATIO_W_TO_H: Float = 3f / 4f

    /**
     * @param box detector output, normalized 0..1 against the upright analysis frame.
     * @param paneRatioWtoH the preview pane's width/height — the viewport crop.
     * @param targetRatioWtoH the saved file's width/height (D9: 0.8 or 1.0).
     * @param mirror true for the front lens, which is flipped into the saved pixels.
     * @param sourceRatioWtoH the analysis frame's aspect; only overridden in tests.
     * @return the box in stored-file coordinates, or null when the subject does not
     *   survive the crops — a person who was visible in the analysis frame can be
     *   entirely outside the saved one, and an empty box is the honest answer.
     */
    fun project(
        box: NormalizedBox?,
        paneRatioWtoH: Float,
        targetRatioWtoH: Float,
        mirror: Boolean,
        sourceRatioWtoH: Float = ANALYSIS_RATIO_W_TO_H,
    ): SubjectBox? {
        if (box == null) return null
        if (!paneRatioWtoH.isFinite() || paneRatioWtoH <= 0f) return null
        if (!targetRatioWtoH.isFinite() || targetRatioWtoH <= 0f) return null
        if (!sourceRatioWtoH.isFinite() || sourceRatioWtoH <= 0f) return null

        var left = box.left
        var top = box.top
        var right = box.right
        var bottom = box.bottom
        if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) return null
        if (right <= left || bottom <= top) return null

        // Two centre crops, in the order the pixels actually go through them.
        var ratio = sourceRatioWtoH
        for (next in floatArrayOf(paneRatioWtoH, targetRatioWtoH)) {
            val keepX = if (next < ratio) next / ratio else 1f
            val keepY = if (next > ratio) ratio / next else 1f
            left = remap(left, keepX)
            right = remap(right, keepX)
            top = remap(top, keepY)
            bottom = remap(bottom, keepY)
            ratio = next
        }

        // Clip to what the file actually contains. Clipping is right here and wrong
        // in `CaptureConditions.parse`: there a broken box is a parse failure with
        // no valid region behind it, whereas here a partly-cropped subject has a
        // real visible part and that part is what §4-1 should centre on.
        val clipLeft = left.coerceIn(0f, 1f)
        val clipTop = top.coerceIn(0f, 1f)
        val clipRight = right.coerceIn(0f, 1f)
        val clipBottom = bottom.coerceIn(0f, 1f)
        if (clipRight <= clipLeft || clipBottom <= clipTop) return null

        return if (mirror) {
            SubjectBox(left = 1f - clipRight, top = clipTop, right = 1f - clipLeft, bottom = clipBottom)
        } else {
            SubjectBox(left = clipLeft, top = clipTop, right = clipRight, bottom = clipBottom)
        }
    }

    /** Rescales one normalized axis through a centre crop that keeps [keep] of it. */
    private fun remap(v: Float, keep: Float): Float =
        if (keep >= 1f) v else (v - (1f - keep) / 2f) / keep
}
