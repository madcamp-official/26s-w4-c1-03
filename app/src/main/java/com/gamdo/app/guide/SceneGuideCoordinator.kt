package com.gamdo.app.guide

import com.gamdo.app.detect.DetectionResult

/** Low-resolution frame signals supplied by the camera analysis adapter. */
data class SceneFrameSignals(
    val rowLuminance: List<Float> = emptyList(),
    val sideEdgeDensity: List<Float> = emptyList(),
)

data class SceneGuideState(
    val observation: SceneObservation,
    val proposal: CompositionProposal,
)

/**
 * Single P2 seam for the camera owner: detection + cheap scene statistics become
 * a proposal and a target that can be passed to AlignmentEngine.
 */
class SceneGuideCoordinator(
    private val structureAnalyzer: SceneStructureAnalyzer = SceneStructureAnalyzer(),
    private val proposalEngine: SceneProposalEngine = SceneProposalEngine(),
) {
    fun update(
        detection: DetectionResult,
        styleTarget: StyleTarget,
        signals: SceneFrameSignals = SceneFrameSignals(),
    ): SceneGuideState {
        val detected = detection.toSceneObservation()
        val structured = structureAnalyzer.analyze(
            SceneStructureInput(
                rowLuminance = signals.rowLuminance,
                sideEdgeDensity = signals.sideEdgeDensity,
                subjectBox = detected.subjectBox,
                subjectKind = detected.subjectKind,
                subjectConfidence = detected.subjectConfidence,
            ),
        )
        val observation = structured.copy(
            // Detection is authoritative for the subject; structure analysis only
            // enriches it with horizon and open-space signals.
            subjectBox = detected.subjectBox,
            subjectKind = detected.subjectKind,
            subjectConfidence = detected.subjectConfidence,
        )
        return SceneGuideState(
            observation = observation,
            proposal = proposalEngine.propose(observation, styleTarget),
        )
    }

    fun reset() {
        proposalEngine.reset()
    }
}
