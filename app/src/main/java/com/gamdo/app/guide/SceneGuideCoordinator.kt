package com.gamdo.app.guide

import com.gamdo.app.detect.DetectionResult

/** Low-resolution frame signals supplied by the camera analysis adapter. */
data class SceneFrameSignals(
    val rowLuminance: List<Float> = emptyList(),
    val sideEdgeDensity: List<Float> = emptyList(),
    /** Optional fixed multi-slot template supplied by the camera owner. */
    val layoutTemplate: LayoutTemplate? = null,
    val layoutTemplateId: String? = null,
)

data class SceneGuideState(
    val observation: SceneObservation,
    val proposal: CompositionProposal,
    val layoutGuide: SceneLayoutGuide,
    val fixedLayout: FixedLayoutGuide? = null,
)

/**
 * Single P2 seam for the camera owner: detection + cheap scene statistics become
 * a proposal and a target that can be passed to AlignmentEngine.
 */
class SceneGuideCoordinator(
    private val structureAnalyzer: SceneStructureAnalyzer = SceneStructureAnalyzer(),
    private val proposalEngine: SceneProposalEngine = SceneProposalEngine(),
    private val layoutGuideEngine: SceneLayoutGuideEngine = SceneLayoutGuideEngine(),
    private val fixedLayoutMatcher: FixedLayoutSlotMatcher = FixedLayoutSlotMatcher(),
    private val autoLayoutResolver: AutoLayoutTemplateResolver = AutoLayoutTemplateResolver(),
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
            subjectOutline = detected.subjectOutline,
            subjectLabels = detected.subjectLabels,
            slotDetections = detected.slotDetections,
        )
        val proposal = proposalEngine.propose(observation, styleTarget)
        val template = signals.layoutTemplate ?:
            signals.layoutTemplateId?.let(LayoutTemplateCatalog::resolve) ?:
            autoLayoutResolver.resolve(observation.slotDetections, styleTarget.layoutTemplateId)
        val fixedLayout = template?.let {
            fixedLayoutMatcher.match(it, observation.slotDetections)
        }
        val layoutGuide = layoutGuideEngine.build(observation, proposal).copy(
            fixedLayout = fixedLayout,
        )
        return SceneGuideState(
            observation = observation,
            proposal = proposal,
            layoutGuide = layoutGuide,
            fixedLayout = fixedLayout,
        )
    }

    fun reset() {
        proposalEngine.reset()
        layoutGuideEngine.reset()
        fixedLayoutMatcher.reset()
        autoLayoutResolver.reset()
    }
}
