package com.gamdo.app.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/** Rolling per-second analysis metrics for the debug HUD (§2-1). */
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
 */
class FrameAnalyzer(
    targetFps: Int = 12,
    private val onStats: (AnalysisStats) -> Unit,
    private val onFrame: (ImageProxy) -> Unit = {},
) : ImageAnalysis.Analyzer {

    private val minIntervalNs = 1_000_000_000L / targetFps

    private var lastProcessedNs = 0L
    private var windowStartNs = 0L
    private var processed = 0
    private var dropped = 0
    private var sumProcessMs = 0.0

    override fun analyze(image: ImageProxy) {
        val now = System.nanoTime()
        if (windowStartNs == 0L) windowStartNs = now
        try {
            if (now - lastProcessedNs < minIntervalNs) {
                dropped++
                return
            }
            lastProcessedNs = now
            val t0 = System.nanoTime()
            onFrame(image)
            sumProcessMs += (System.nanoTime() - t0) / 1_000_000.0
            processed++
        } finally {
            image.close()
            maybeEmit(now)
        }
    }

    private fun maybeEmit(now: Long) {
        if (now - windowStartNs < 1_000_000_000L) return
        val total = processed + dropped
        onStats(
            AnalysisStats(
                processMs = if (processed > 0) sumProcessMs / processed else 0.0,
                fps = processed,
                dropRatePercent = if (total > 0) dropped * 100 / total else 0,
            ),
        )
        windowStartNs = now
        processed = 0
        dropped = 0
        sumProcessMs = 0.0
    }
}
