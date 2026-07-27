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
    val layoutState: GuideLayoutState = GuideLayoutState.Searching,
)

/**
 * Single P2 seam for the camera owner: detection + cheap scene statistics become
 * a proposal and a target that can be passed to AlignmentEngine.
 */
class SceneGuideCoordinator(
    private val structureAnalyzer: SceneStructureAnalyzer = SceneStructureAnalyzer(),
    private val proposalEngine: SceneProposalEngine = SceneProposalEngine(),
    private val layoutGuideEngine: SceneLayoutGuideEngine = SceneLayoutGuideEngine(),
    private val autoLayoutResolver: AutoLayoutTemplateResolver = AutoLayoutTemplateResolver(),
) {
    private var layoutState: GuideLayoutState = GuideLayoutState.Searching
    private var manualTemplateId: String? = null
    private var fixedBaseTemplate: LayoutTemplate? = null

    val currentLayoutState: GuideLayoutState get() = layoutState

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
            objectsFresh = detected.objectsFresh,
        )
        val proposal = proposalEngine.propose(observation, styleTarget)
        val explicitTemplate = signals.layoutTemplate
            ?: signals.layoutTemplateId?.let(LayoutTemplateCatalog::resolve)
            ?: styleTarget.layoutTemplateId?.let(LayoutTemplateCatalog::resolve)
        if (explicitTemplate != null && manualTemplateId == null) {
            manualTemplateId = explicitTemplate.id
            fixedBaseTemplate = explicitTemplate
            layoutState = GuideLayoutState.Fixed(
                GenericLayoutSynthesizer.transform(explicitTemplate, styleTarget),
                LayoutSource.MANUAL,
            )
        }
        val baseTemplate = when (val current = layoutState) {
            is GuideLayoutState.Fixed -> fixedBaseTemplate ?: current.template
            GuideLayoutState.Searching -> autoLayoutResolver.resolve(
                detections = observation.slotDetections,
                objectsFresh = observation.objectsFresh,
                styleTarget = styleTarget,
            )?.also {
                fixedBaseTemplate = it
                layoutState = GuideLayoutState.Fixed(GenericLayoutSynthesizer.transform(it, styleTarget), LayoutSource.AUTO)
            }
        }
        val template = baseTemplate?.let { GenericLayoutSynthesizer.transform(it, styleTarget) }
        val fixedLayout = template?.let { FixedLayoutGuide(it) }
        val layoutGuide = layoutGuideEngine.build(observation, proposal).copy(
            fixedLayout = fixedLayout,
        )
        return SceneGuideState(
            observation = observation,
            proposal = proposal,
            layoutGuide = layoutGuide,
            fixedLayout = fixedLayout,
            layoutState = layoutState,
        )
    }

    fun selectManualLayout(templateId: String, styleTarget: StyleTarget = StyleTarget()): Boolean {
        val template = LayoutTemplateCatalog.resolve(templateId) ?: return false
        manualTemplateId = templateId
        autoLayoutResolver.reset()
        fixedBaseTemplate = template
        layoutState = GuideLayoutState.Fixed(
            GenericLayoutSynthesizer.transform(template, styleTarget),
            LayoutSource.MANUAL,
        )
        return true
    }

    fun rescan() {
        manualTemplateId = null
        fixedBaseTemplate = null
        layoutState = GuideLayoutState.Searching
        autoLayoutResolver.reset()
    }

    fun reset() {
        proposalEngine.reset()
        layoutGuideEngine.reset()
        autoLayoutResolver.reset()
        manualTemplateId = null
        fixedBaseTemplate = null
        layoutState = GuideLayoutState.Searching
    }
}
