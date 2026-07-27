package com.gamdo.app.guide

import kotlin.math.abs

/**
 * Tuning for [OverlayStabilizer]. Every value arrives from
 * `assets/guide_config.json` → `alignment.overlayStabilizer` (CFG-1); the
 * defaults here are the fallback for a missing asset.
 *
 * Frame counts are in analysis frames at `stability.sequenceFps` (12 fps →
 * 83ms/frame), matching `FrameAnalyzer(targetFps = 12)`.
 */
data class OverlayStabilizerConfig(
    /**
     * Consecutive engine-aligned frames before the bracket turns sage. 3 ≈ 250ms:
     * short enough that the cue still feels immediate, long enough that a subject
     * sitting on the IoU threshold cannot strobe it.
     */
    val alignedEnterFrames: Int = 3,
    /**
     * Consecutive engine-not-aligned frames before the sage cue is withdrawn.
     * Deliberately longer than [alignedEnterFrames]: the colour change is the only
     * "you're framed" feedback there is (D2-3), so revoking it mid shutter-press
     * is the worse error. Same asymmetric-Schmitt shape as `HorizonGate`.
     */
    val alignedExitFrames: Int = 5,
    /**
     * How long the silhouette survives a detection dropout. 6 ≈ 500ms, which
     * covers the 1–3 frame pose gaps ML Kit produces in low light without making
     * a genuine walk-out feel sticky.
     */
    val silhouetteHoldFrames: Int = 6,
    /** Same hold applied to whole-overlay visibility. */
    val visibleHoldFrames: Int = 6,
    /**
     * Slew limit: the largest distance any rect edge may travel in one frame, in
     * normalized units. 0.01/frame = 0.12/s at 12fps — a deliberate glide. Above
     * roughly this rate the eye reads the move as a snap rather than as motion.
     */
    val maxStepPerFrameNorm: Float = 0.01f,
) {
    init {
        require(alignedEnterFrames >= 1) { "alignedEnterFrames must be >= 1" }
        require(alignedExitFrames >= 1) { "alignedExitFrames must be >= 1" }
        require(silhouetteHoldFrames >= 0) { "silhouetteHoldFrames must be >= 0" }
        require(visibleHoldFrames >= 0) { "visibleHoldFrames must be >= 0" }
        require(maxStepPerFrameNorm > 0f) { "maxStepPerFrameNorm must be > 0" }
    }

    companion object {
        /**
         * Exact identity. Used by the §0.4 harness to measure the unstabilized
         * baseline through the same production path, so before/after numbers are
         * comparable rather than produced by two different code paths.
         */
        val PassThrough = OverlayStabilizerConfig(
            alignedEnterFrames = 1,
            alignedExitFrames = 1,
            silhouetteHoldFrames = 0,
            visibleHoldFrames = 0,
            maxStepPerFrameNorm = 1f,
        )
    }
}

/**
 * Display-side stabilizer for [OverlayProjection] (§3-2, "깜빡임 금지").
 *
 * [AlignmentEngine] already smooths the target rect, holds the last stable value
 * under low confidence, and hides the overlay when the target will not settle —
 * those three stay exactly as they are. What the engine does **not** do is
 * damp its own two booleans: `aligned` is recomputed from the raw, unsmoothed
 * person box every frame, and `silhouette` is dropped the instant a single frame
 * misses the person. Both surface as visible blinking, and both have to be fixed
 * outside the engine because it is a frozen 담당 B module.
 *
 * This class is a pure state machine over frames — no Android, no coroutines —
 * so the §0.4 harness can drive the real path on the JVM. One instance per
 * camera session; call [reset] whenever the analyzer detaches or the style target
 * changes, in step with `AlignmentEngine.reset()`.
 */
class OverlayStabilizer(
    private val config: OverlayStabilizerConfig = OverlayStabilizerConfig(),
) {
    private var visible = false
    private var hiddenRun = 0

    private var aligned = false
    private var alignedDisagreeRun = 0

    private var heldSilhouette: RectN? = null
    private var silhouetteMissingRun = 0

    private var lastFrame: RectN? = null
    private var lastSilhouette: RectN? = null

    fun stabilize(raw: OverlayProjection): OverlayProjection {
        updateVisibility(raw.visible)
        updateAligned(raw.aligned)
        updateSilhouette(raw.silhouetteBounds)

        // While hidden there is nothing on screen to jump, so snapping is free and
        // avoids a long glide on the frame the overlay reappears.
        val frame = if (visible) slew(lastFrame, raw.targetFrame) else raw.targetFrame
        lastFrame = frame

        val silhouette = heldSilhouette?.takeIf { visible }?.let { target ->
            slew(lastSilhouette, target).also { lastSilhouette = it }
        }
        if (silhouette == null) lastSilhouette = null

        return OverlayProjection(
            targetFrame = frame,
            silhouetteBounds = silhouette,
            horizonY = raw.horizonY,
            visible = visible,
            // `aligned` is a property of a visible bracket; a hidden overlay is
            // never "aligned", or the cue would re-appear already green.
            aligned = aligned && visible,
        )
    }

    fun reset() {
        visible = false
        hiddenRun = 0
        aligned = false
        alignedDisagreeRun = 0
        heldSilhouette = null
        silhouetteMissingRun = 0
        lastFrame = null
        lastSilhouette = null
    }

    private fun updateVisibility(rawVisible: Boolean) {
        if (rawVisible) {
            visible = true
            hiddenRun = 0
            return
        }
        // Nothing to hold if the overlay was never shown.
        if (!visible) return
        hiddenRun++
        if (hiddenRun > config.visibleHoldFrames) {
            visible = false
            hiddenRun = 0
            heldSilhouette = null
            silhouetteMissingRun = 0
        }
    }

    private fun updateAligned(rawAligned: Boolean) {
        if (rawAligned == aligned) {
            alignedDisagreeRun = 0
            return
        }
        alignedDisagreeRun++
        val required = if (rawAligned) config.alignedEnterFrames else config.alignedExitFrames
        if (alignedDisagreeRun >= required) {
            aligned = rawAligned
            alignedDisagreeRun = 0
        }
    }

    private fun updateSilhouette(rawBounds: RectN?) {
        if (rawBounds != null) {
            heldSilhouette = rawBounds
            silhouetteMissingRun = 0
            return
        }
        if (heldSilhouette == null) return
        silhouetteMissingRun++
        if (silhouetteMissingRun > config.silhouetteHoldFrames) {
            heldSilhouette = null
            silhouetteMissingRun = 0
        }
    }

    /** Moves each edge of [previous] toward [target] by at most the slew limit. */
    private fun slew(previous: RectN?, target: RectN): RectN {
        if (previous == null) return target
        val step = config.maxStepPerFrameNorm
        return RectN(
            left = approach(previous.left, target.left, step),
            top = approach(previous.top, target.top, step),
            right = approach(previous.right, target.right, step),
            bottom = approach(previous.bottom, target.bottom, step),
        )
    }

    private fun approach(from: Float, to: Float, step: Float): Float {
        val delta = to - from
        if (abs(delta) <= step) return to
        return from + if (delta > 0f) step else -step
    }
}
