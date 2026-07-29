package com.gamdo.app.camera

import kotlin.math.roundToInt

/**
 * One second of preview frames (§7-1 "프리뷰 30FPS").
 *
 * [fps] is frames over the window that **actually elapsed**, not over a nominal
 * second, so a stall shows up as a lower rate instead of being hidden by a divisor
 * that was assumed rather than measured.
 */
data class PreviewStats(
    val fps: Int,
    val frames: Int,
    val windowMs: Double,
)

/**
 * Counts preview frames (W3-2) — deliberately *not* the same quantity as
 * [AnalysisStats.fps].
 *
 * The HUD used to print the analysis rate alone, which on SM-G970N reads ~3fps
 * while the preview is visibly smooth. Both numbers are real and neither answers
 * the other's question: analysis fps is how often the detection stack ran, preview
 * fps is how often the image on screen changed. §7-1 asks about the second one,
 * and nothing measured it.
 *
 * Fed from `PreviewView.setFrameUpdateListener`, which fires once per delivered
 * preview frame. The caller passes `System.nanoTime()` rather than the
 * `SurfaceTexture` timestamp the callback also carries: that timestamp is in the
 * camera's own timebase (CLOCK_MONOTONIC on most devices, CLOCK_BOOTTIME on some)
 * and mixing timebases silently produces a plausible wrong number. Delivery time
 * is also the honest reading of the question — how often the preview updated.
 *
 * Not thread-safe, and does not need to be: `onSurfaceTextureUpdated` is delivered
 * on the main thread and the meter is attached with a direct executor, so every
 * call arrives on that one thread.
 */
class PreviewFrameMeter(private val windowNs: Long = 1_000_000_000L) {

    init {
        require(windowNs > 0L) { "windowNs must be > 0, was $windowNs" }
    }

    private var windowStartNs: Long? = null
    private var frames = 0

    /**
     * Records one preview frame delivered at [nowNs], returning a report each time
     * a window closes and null otherwise.
     *
     * The frame that closes a window also opens the next one, so no frame is
     * counted twice and none falls between two windows.
     */
    fun onFrame(nowNs: Long): PreviewStats? {
        val start = windowStartNs
        if (start == null) {
            windowStartNs = nowNs
            frames = 0
            return null
        }
        frames++
        val elapsedNs = nowNs - start
        if (elapsedNs < windowNs) return null

        val elapsedSeconds = elapsedNs / 1_000_000_000.0
        val stats = PreviewStats(
            fps = (frames / elapsedSeconds).roundToInt(),
            frames = frames,
            windowMs = elapsedNs / 1_000_000.0,
        )
        windowStartNs = nowNs
        frames = 0
        return stats
    }

    /**
     * Drops the open window. Called when the preview detaches: without it the
     * frame that arrives after a background trip would close a window spanning the
     * whole absence and report a rate no one experienced.
     */
    fun reset() {
        windowStartNs = null
        frames = 0
    }
}
