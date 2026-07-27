package com.gamdo.app.detect

import com.gamdo.app.guide.SceneFrameSignals
import com.gamdo.app.guide.SceneGuideCoordinator
import com.gamdo.app.guide.SceneGuideState
import com.gamdo.app.guide.StyleTarget

/**
 * Runtime façade for the camera analysis thread. P1 only needs to provide the
 * current StyleTarget and optional thumbnail statistics; detector lifecycle stays
 * in this P2-owned seam.
 */
class SceneAwareFrameProcessor(
    private val detector: SceneDetector,
    private val coordinator: SceneGuideCoordinator = SceneGuideCoordinator(),
) {
    fun process(
        frame: AnalysisFrame,
        styleTarget: StyleTarget,
        signals: SceneFrameSignals = SceneFrameSignals(),
    ): SceneGuideState {
        val detection = detector.detect(frame)
        return coordinator.update(detection, styleTarget, signals)
    }

    fun reset() = coordinator.reset()

    fun close() = detector.close()
}
