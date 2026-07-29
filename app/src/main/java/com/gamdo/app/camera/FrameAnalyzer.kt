package com.gamdo.app.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/**
 * Rolling per-second **analysis** metrics for the debug HUD (§2-1).
 *
 * [fps] is how often the detection stack ran, which is not how often the preview
 * updated — see [PreviewFrameMeter] for that one. The HUD labels both, because
 * printing this number alone read as a failed §7-1 preview target that had never
 * been measured.
 *
 * [dropRatePercent] is what the throttle refused. It is expected to be 0 on this
 * device: see [AnalysisCadence].
 */
data class AnalysisStats(
    val processMs: Double,
    val fps: Int,
    val dropRatePercent: Int,
)

/**
 * ImageAnalysis analyzer that throttles to [targetFps] (dropping in-between
 * frames so the preview never stalls), hands each processed frame to [onFrame],
 * and reports processing time / fps / drop-rate once per second via [onStats].
 *
 * Every frame is closed exactly once — required or the KEEP_ONLY_LATEST pipeline
 * would starve.
 *
 * @param targetFps analysis ceiling, from `features.analysisTargetFps` in
 *   `assets/guide_config.json` (CFG-1). Deliberately has **no default**: a default
 *   here would be a second place the number lives, and the one that wins on device
 *   would depend on which call site forgot to pass it.
 */
class FrameAnalyzer(
    targetFps: Int,
    private val onStats: (AnalysisStats) -> Unit,
    private val onFrame: (ImageProxy) -> Unit = {},
) : ImageAnalysis.Analyzer {

    private val cadence = AnalysisCadence(targetFps)
    private val window = AnalysisStatsWindow()

    override fun analyze(image: ImageProxy) {
        val now = System.nanoTime()
        try {
            if (!cadence.shouldProcess(now)) {
                window.onDropped()
                return
            }
            val t0 = System.nanoTime()
            onFrame(image)
            window.onProcessed((System.nanoTime() - t0) / 1_000_000.0)
        } finally {
            image.close()
            // Closed on a stamp taken *after* the work, not on `now`. `now` is the
            // arrival time; using it here left each window's last frame counted but
            // not timed, which inflated the rate on top of the divisor bug that
            // [AnalysisStatsWindow] documents.
            window.maybeEmit(System.nanoTime())?.let(onStats)
        }
    }
}
