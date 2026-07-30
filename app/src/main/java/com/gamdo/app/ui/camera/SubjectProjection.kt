package com.gamdo.app.ui.camera

import com.gamdo.app.camera.CaptureGeometry
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
 * ## Why this takes a rectangle and not two aspect ratios
 *
 * It used to take `paneRatioWtoH` and `targetRatioWtoH` and *infer* the crops from
 * them — viewport first, then aspect — on the reasoning that CameraX attaches the
 * `PreviewView`'s viewport as the capture's `cropRect`, so the pane's aspect is a
 * real term. The KDoc even carried a device measurement for it: "sensor 3024×4032,
 * pane 1080×1500, saved file **2904×3630** … Exact."
 *
 * That number was never measured. It was the model's own prediction written up as
 * evidence, and the real measurement disagrees. From `CameraScreen`'s
 * `CaptureLatency geometry` line on SM-G970N, 2026-07-30: **3024×3780** rear and
 * **2736×3420** front — both exactly 4:5 at the sensor's *full width*. The viewport
 * crop takes no width at all on this device, so the projection was applying two
 * centre crops where the pixels went through one.
 *
 * The error was quantified before this was changed (owner decision 2026-07-30):
 *
 * | 비율 | subject | inferred x,y | actual x,y | error |
 * |---|---|---|---|---|
 * | 4:5 | centre | 0.500, 0.500 | 0.500, 0.500 | 0%, 0% |
 * | 4:5 | upper right | 0.790, 0.253 | 0.750, 0.287 | 4.0%, 3.4% |
 * | 4:5 | at the edge | 0.963, 0.068 | 0.900, 0.127 | 6.3%, 5.9% |
 * | 1:1 | at the edge | 0.963, **−0.041** | 0.900, 0.033 | 6.3%, 7.4% |
 * | 16:9 | anywhere | — | — | 0% |
 *
 * Zero at the centre, worst at the edges, and at 1:1 the inferred box left the
 * frame entirely. 16:9 shows nothing because 0.5625 is *narrower* than the pane's
 * 0.6475, so the aspect crop always binds and the phantom pane crop is masked —
 * which is why 16:9 was the wrong ratio to try to see this with, and 4:5 the right
 * one.
 *
 * So the inference is gone. [captureGeometryFor][com.gamdo.app.camera.captureGeometryFor]
 * already computes the exact rectangle the shutter reads — it has to, the pixels
 * come from it — and `CameraController.capture` now hands that plan back with the
 * bitmap. This function reads the crop that *happened* instead of predicting the
 * crop that would. It is therefore right on a device whose viewport does narrow the
 * width too, which neither the old model nor "pass the pane ratio as a no-op" would
 * have been.
 *
 * ## Coordinate spaces
 *
 * The plan's `src` rect is in the **decoded buffer's** coordinates, pre-rotation.
 * The detector's box is normalized against the **upright** frame. So step 1 rotates
 * the rect into upright space, and everything after it is one remap.
 *
 * ## Mirroring
 *
 * The front lens is mirrored into the saved pixels to match the preview, but the
 * detector sees the unmirrored frame. `captureGeometryFor` already undid the mirror
 * when it built the `src` rect (its step 4), so that rect and the detector's box are
 * in the same unmirrored space and the flip belongs last — after the remap, on the
 * way out to file coordinates.
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
     * @param geometry the plan the shutter actually applied, from
     *   `CameraController.capture`. Null when no capture geometry is available, which
     *   drops the subject rather than guessing one.
     * @param bufferWidth the decoded capture buffer's width, which [geometry]'s
     *   `src` rect is expressed against.
     * @param bufferHeight likewise.
     * @param sourceRatioWtoH the analysis frame's aspect. Equal to the buffer's
     *   upright aspect on this device, so the correction it drives is a no-op here;
     *   overridden in tests.
     * @return the box in stored-file coordinates, or null when the subject does not
     *   survive the crop — a person who was visible in the analysis frame can be
     *   entirely outside the saved one, and an empty box is the honest answer.
     */
    fun project(
        box: NormalizedBox?,
        geometry: CaptureGeometry?,
        bufferWidth: Int,
        bufferHeight: Int,
        sourceRatioWtoH: Float = ANALYSIS_RATIO_W_TO_H,
    ): SubjectBox? {
        if (box == null || geometry == null) return null
        if (bufferWidth <= 0 || bufferHeight <= 0) return null
        if (geometry.srcWidth <= 0 || geometry.srcHeight <= 0) return null
        if (!sourceRatioWtoH.isFinite() || sourceRatioWtoH <= 0f) return null

        var left = box.left
        var top = box.top
        var right = box.right
        var bottom = box.bottom
        if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) return null
        if (right <= left || bottom <= top) return null

        // 1. Rotate the kept rect out of buffer coordinates into the upright frame.
        //    This is the inverse of `captureGeometryFor`'s step 5, and it has to
        //    agree with it exactly or the box lands on the wrong axis — clockwise,
        //    y-down, matching `Matrix.postRotate`.
        val quarterTurn = geometry.rotationDegrees == 90 || geometry.rotationDegrees == 270
        val uprightW = if (quarterTurn) bufferHeight else bufferWidth
        val uprightH = if (quarterTurn) bufferWidth else bufferHeight
        val sx = geometry.srcX
        val sy = geometry.srcY
        val sw = geometry.srcWidth
        val sh = geometry.srcHeight
        val winX: Int
        val winY: Int
        val winW: Int
        val winH: Int
        when (geometry.rotationDegrees) {
            90 -> { winX = bufferHeight - sy - sh; winY = sx; winW = sh; winH = sw }
            180 -> { winX = bufferWidth - sx - sw; winY = bufferHeight - sy - sh; winW = sw; winH = sh }
            270 -> { winX = sy; winY = bufferWidth - sx - sw; winW = sh; winH = sw }
            else -> { winX = sx; winY = sy; winW = sw; winH = sh }
        }
        // A rect that does not fit the frame it is supposed to be a sub-rect of means
        // the buffer size and the plan do not belong together — a caller pairing one
        // capture's plan with another's dimensions. There is no meaningful projection
        // through it, and a silently rescaled box would be the failure this whole
        // file exists to prevent.
        if (winX < 0 || winY < 0 || winX + winW > uprightW || winY + winH > uprightH) return null

        // 2. The detector's frame and the capture buffer are both the full sensor
        //    field of view; if their aspects disagree, the narrower one is a centre
        //    crop of the wider. Both are 0.75 on this device, so `keep` is exactly 1
        //    and this is arithmetically a no-op — kept rather than asserted away
        //    because the analysis stream's aspect is a resolution-selector outcome,
        //    not a guarantee.
        val bufferRatio = uprightW.toFloat() / uprightH.toFloat()
        val fovKeepX = if (bufferRatio < sourceRatioWtoH) bufferRatio / sourceRatioWtoH else 1f
        val fovKeepY = if (bufferRatio > sourceRatioWtoH) sourceRatioWtoH / bufferRatio else 1f
        left = remap(left, fovKeepX)
        right = remap(right, fovKeepX)
        top = remap(top, fovKeepY)
        bottom = remap(bottom, fovKeepY)

        // 3. Remap into the window the plan keeps. Not a centre crop any more — the
        //    plan's rect can sit anywhere, because a viewport `cropRect` is not
        //    obliged to be centred and `captureGeometryFor` carries wherever it was.
        val keepLeft = winX.toFloat() / uprightW.toFloat()
        val keepRight = (winX + winW).toFloat() / uprightW.toFloat()
        val keepTop = winY.toFloat() / uprightH.toFloat()
        val keepBottom = (winY + winH).toFloat() / uprightH.toFloat()
        val spanX = keepRight - keepLeft
        val spanY = keepBottom - keepTop
        if (spanX <= 0f || spanY <= 0f) return null
        left = (left - keepLeft) / spanX
        right = (right - keepLeft) / spanX
        top = (top - keepTop) / spanY
        bottom = (bottom - keepTop) / spanY

        // Clip to what the file actually contains. Clipping is right here and wrong
        // in `CaptureConditions.parse`: there a broken box is a parse failure with
        // no valid region behind it, whereas here a partly-cropped subject has a
        // real visible part and that part is what §4-1 should centre on.
        val clipLeft = left.coerceIn(0f, 1f)
        val clipTop = top.coerceIn(0f, 1f)
        val clipRight = right.coerceIn(0f, 1f)
        val clipBottom = bottom.coerceIn(0f, 1f)
        if (clipRight <= clipLeft || clipBottom <= clipTop) return null

        return if (geometry.mirror) {
            SubjectBox(left = 1f - clipRight, top = clipTop, right = 1f - clipLeft, bottom = clipBottom)
        } else {
            SubjectBox(left = clipLeft, top = clipTop, right = clipRight, bottom = clipBottom)
        }
    }

    /** Rescales one normalized axis through a centre crop that keeps [keep] of it. */
    private fun remap(v: Float, keep: Float): Float =
        if (keep >= 1f) v else (v - (1f - keep) / 2f) / keep
}
