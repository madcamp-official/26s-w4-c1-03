package com.gamdo.app.ui.camera

/**
 * Normalized → view-pixel mapping for the camera overlay, and the one place that
 * decides **which coordinates get mirrored on the front lens**.
 *
 * ## The two spaces
 *
 * `CameraController` mirrors the front lens into both the preview and the saved
 * pixels (`CaptureGeometry.mirror`), but the detector sees the raw,
 * unmirrored sensor frame. So there are two normalized spaces on screen, and they
 * differ by a horizontal flip only when the front lens is active:
 *
 *  - **analysis space** — anything the detector produced: face boxes, the person
 *    centre, a segmentation outline. Mirrored to reach the screen.
 *  - **composition space** — anything authored as "where this should end up in the
 *    photo": the style target's `subjectAnchorX`, the silhouette derived from it,
 *    a fixed layout template's slots. **Not** mirrored, because the screen and the
 *    saved file are already the same space.
 *
 * ## The defect this exists to prevent (review_report #9)
 *
 * `CameraOverlay` had one mapping function that mirrored everything it was handed.
 * That is right for detections and wrong for targets. `presets.json` puts
 * `candid_feed` and `night_street` at `third_left`; on the front lens their
 * bracket was drawn at 2/3, the user obligingly framed themselves there, and the
 * saved photo — mirrored back — put the subject on the right third. A `third_left`
 * preset produced right-third photos, and only on the selfie camera.
 *
 * Splitting the function is the fix. Naming the parameter after the *space of the
 * input* rather than passing a boolean is the part that keeps it fixed: a new call
 * site has to say which kind of coordinate it holds, and that question has an
 * obvious answer at every site.
 */
object OverlayMapping {

    /** Which normalized space a coordinate is expressed in. */
    enum class Space {
        /** Detector output. Mirrored on the front lens to reach the screen. */
        ANALYSIS,

        /** A composition target. Already in screen/saved-file space. */
        COMPOSITION,
    }

    /** A point in view pixels. */
    data class ViewPoint(val x: Float, val y: Float)

    /** A rect in view pixels, normalized so left ≤ right and top ≤ bottom. */
    data class ViewRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }

    /**
     * Maps a normalized point onto the view with the same FILL_CENTER transform
     * `PreviewView` uses. Preview and analysis are both pinned to 4:3 by
     * `CameraController`, so one transform serves both.
     */
    fun point(
        nx: Float,
        ny: Float,
        space: Space,
        mirror: Boolean,
        frameWidth: Int,
        frameHeight: Int,
        viewWidth: Float,
        viewHeight: Float,
    ): ViewPoint {
        val arAnalysis = frameWidth.toFloat() / frameHeight.toFloat()
        val arView = viewWidth / viewHeight
        val contentW: Float
        val contentH: Float
        val offX: Float
        val offY: Float
        if (arView > arAnalysis) {
            // fill width, crop height
            contentW = viewWidth
            contentH = viewWidth / arAnalysis
            offX = 0f
            offY = (viewHeight - contentH) / 2f
        } else {
            // fill height, crop width
            contentH = viewHeight
            contentW = viewHeight * arAnalysis
            offX = (viewWidth - contentW) / 2f
            offY = 0f
        }
        val fx = if (mirror && space == Space.ANALYSIS) 1f - nx else nx
        return ViewPoint(offX + fx * contentW, offY + ny * contentH)
    }

    fun rect(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        space: Space,
        mirror: Boolean,
        frameWidth: Int,
        frameHeight: Int,
        viewWidth: Float,
        viewHeight: Float,
    ): ViewRect {
        val a = point(left, top, space, mirror, frameWidth, frameHeight, viewWidth, viewHeight)
        val b = point(right, bottom, space, mirror, frameWidth, frameHeight, viewWidth, viewHeight)
        return ViewRect(
            left = minOf(a.x, b.x),
            top = minOf(a.y, b.y),
            right = maxOf(a.x, b.x),
            bottom = maxOf(a.y, b.y),
        )
    }
}
