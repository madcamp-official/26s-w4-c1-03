package com.gamdo.app.camera

/**
 * The analysis throttle, as a decision separable from the CameraX frame it acts on.
 *
 * Extracted from [FrameAnalyzer] so the one rule that governs how often the whole
 * detection stack runs can be tested on the JVM. Nothing here touches Android;
 * `analyze()` supplies the clock.
 *
 * **Read the drop rate before treating this as a lever.** The gate only fires when
 * frames arrive faster than [targetFps], and on SM-G970N they do not: one analysed
 * frame costs 178-405ms (median 208.8ms warm, 178.5ms cool, 2026-07-29) while a
 * 12fps target asks only for 83.3ms of spacing. `STRATEGY_KEEP_ONLY_LATEST` hands
 * the next frame over after `analyze()` returns, so the interval this sees is the
 * processing cost, not the preview's 33ms. Throughput there is set by model cost
 * alone and lowering [targetFps] to 8 changes nothing — see
 * `AnalysisCadenceTest.dropping the target to 8fps drops no more frames than 12fps
 * at the measured cost`.
 *
 * It is still the right place for the cadence to live: it is the ceiling, and it
 * becomes a real ceiling on any device (or any future model set) fast enough to
 * exceed it.
 */
class AnalysisCadence(val targetFps: Int) {

    init {
        // Not defensive: `1_000_000_000L / 0` throws ArithmeticException on the
        // analysis thread, so a config typo would surface as a crash mid-preview
        // rather than as a rejected value at construction.
        require(targetFps >= 1) { "targetFps must be >= 1, was $targetFps" }
    }

    /** Minimum spacing between two processed frames. */
    val minIntervalNs: Long = 1_000_000_000L / targetFps

    /**
     * Null until a frame has been processed, rather than 0.
     *
     * The distinction is invisible on device — `System.nanoTime()` is far larger
     * than any interval, so `now - 0` always cleared the gate — but under a
     * synthetic clock starting near zero the first frame was dropped. Making
     * "nothing processed yet" explicit is what lets the tests use a clock they
     * control.
     */
    private var lastProcessedNs: Long? = null

    /**
     * Whether the frame arriving at [nowNs] should be analysed. Advances the clock
     * only when it answers true, so a run of dropped frames is still measured from
     * the last frame actually processed.
     */
    fun shouldProcess(nowNs: Long): Boolean {
        val last = lastProcessedNs
        if (last != null && nowNs - last < minIntervalNs) return false
        lastProcessedNs = nowNs
        return true
    }

    /** Forgets the last processed frame, so the next one is unconditional. */
    fun reset() {
        lastProcessedNs = null
    }
}
