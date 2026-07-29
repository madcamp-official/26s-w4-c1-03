package com.gamdo.app.camera

/**
 * Accumulates one window of analysis metrics for the debug HUD (§2-1, §7-1).
 *
 * Extracted from `FrameAnalyzer` for the same reason [AnalysisCadence] was — the
 * arithmetic is the part that can be wrong, and it cannot be tested while it sits
 * next to an `ImageProxy`.
 *
 * **[AnalysisStats.fps] is frames over the window that actually elapsed.** The
 * version this replaces reported the raw count: it checked only that *at least* a
 * second had passed, and that check fires on the first frame arriving after the
 * second is up. At the measured 239ms per frame the window really closes at
 * ~1.195s, so five frames were reported as "5fps" when the honest rate is 4.2.
 * The HUD read `분석 239.3ms · 6fps` on SM-G970N and those two numbers cannot both
 * be true — six frames of 239ms is 1.43 seconds of work inside one second.
 * [PreviewFrameMeter], written for W3-2, already states and computes this
 * correctly; fixing the preview label while leaving the analysis number inflated
 * would have corrected the wording and kept the error.
 *
 * The caller must close the window on a timestamp taken **after** the frame's
 * work, not on the one it captured when the frame arrived. `FrameAnalyzer` used
 * the arrival stamp and then reused it as the next window's start, so each
 * window's last frame contributed its count but not its duration — inflating the
 * rate a second time, independently of the divisor above.
 */
class AnalysisStatsWindow(private val windowNs: Long = 1_000_000_000L) {

    init {
        require(windowNs > 0L) { "windowNs must be > 0, was $windowNs" }
    }

    private var startNs = 0L
    private var started = false
    private var processed = 0
    private var dropped = 0
    private var sumProcessMs = 0.0

    fun onProcessed(processMs: Double) {
        processed++
        sumProcessMs += processMs
    }

    fun onDropped() {
        dropped++
    }

    /**
     * Emits and rolls the window once [windowNs] has genuinely elapsed since the
     * last close, otherwise null. The first call only starts the clock.
     */
    fun maybeEmit(nowNs: Long): AnalysisStats? {
        if (!started) {
            started = true
            startNs = nowNs
            return null
        }
        val elapsedNs = nowNs - startNs
        if (elapsedNs < windowNs) return null

        val total = processed + dropped
        val stats = AnalysisStats(
            processMs = if (processed > 0) sumProcessMs / processed else 0.0,
            // Rounded down, not nearest: a HUD that rounds 4.7 up to 5 is claiming
            // a rate the frame cost does not support, which is the whole defect.
            fps = (processed * 1_000_000_000.0 / elapsedNs).toInt(),
            dropRatePercent = if (total > 0) dropped * 100 / total else 0,
        )
        startNs = nowNs
        processed = 0
        dropped = 0
        sumProcessMs = 0.0
        return stats
    }
}
