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
 * That number was never measured; it was the model's own prediction written up as
 * evidence. What retired the inference, though, is not that one figure being wrong.
 * It is that **the thing it was inferring changed underneath it, on one device,
 * within one day, and nothing noticed.**
 *
 * Two readings of `CameraScreen`'s `CaptureLatency geometry` line, SM-G970N,
 * 2026-07-30, at 4:5:
 *
 * | build | saved | what the viewport did |
 * |---|---|---|
 * | before the redesign merges | **3024×3780** (and 2736×3420 front) | took no width — full sensor |
 * | after them, 5 shots, cold and warm | **2610×3263** | took the pane's 0.6475 |
 *
 * 2610 is 4032 × 0.6475 exactly, so in the second state the pane aspect *is* the
 * viewport crop and the old inference is right — measured max error across the whole
 * frame **0.00027**, which is integer rounding. In the first state it was wrong by
 * up to **0.084** at 4:5 and **0.105** at 1:1, both at the frame corner, and at 1:1
 * it put the box outside the photograph (y = −0.041 at a sampled edge point).
 * Nothing downstream rejects a negative normalized coordinate; §4-1 would have
 * centred a crop on it.
 *
 * Neither state is exotic and both came from this repository. A model that is exact
 * in one and 10% out in the other, with no test able to tell which one is live, is
 * the defect — the 6% was only the symptom.
 *
 * 16:9 shows **0% in both states**, because 0.5625 is narrower than the pane's
 * 0.6475, so the aspect crop always binds and the viewport term is masked whether it
 * is real or phantom. That is why 16:9 was the wrong ratio to try to see any of this
 * with, and 4:5 the right one.
 *
 * So the inference is gone. [captureGeometryFor][com.gamdo.app.camera.captureGeometryFor]
 * already computes the exact rectangle the shutter reads — it has to, the pixels
 * come from it — and `CameraController.capture` now hands that plan back with the
 * bitmap. This function reads the crop that *happened* instead of predicting the
 * crop that would, so it is right in both states above and in the one after them,
 * which is the property the old signature could not have.
 *
 * ## Verified end to end on device, 2026-07-30
 *
 * Five 4:5 captures with a person, SM-G970N rear. For each, the stored
 * `conditions_json.subject` was checked against a derivation done independently of
 * this code — the window taken from the *measured* `saved=2610x3263` alone, applied
 * to that row's own `analysis_json.personBox`. **Max error 7.3e-08**, which is
 * float32 and not arithmetic. A sixth capture of a ceiling had no detection and
 * correctly stored no subject.
 *
 * And the honest part: on those same five photos the retired inference differs by at
 * most **0.00016**, with one subject at centreX 0.708 and no box clipping to mask it.
 * In this viewport state the old model was already right. **This change did not
 * recover a live 6% error** — it removed a dependency on which of two states the
 * build happens to be in, where one of them costs 0.084.
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
