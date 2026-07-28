package com.gamdo.app.guide

import com.gamdo.app.detect.DetectionResult

/** Low-resolution frame signals supplied by the camera analysis adapter. */
data class SceneFrameSignals(
    val rowLuminance: List<Float> = emptyList(),
    val sideEdgeDensity: List<Float> = emptyList(),
    /** Optional fixed multi-slot template supplied by the camera owner. */
    val layoutTemplate: LayoutTemplate? = null,
    val layoutTemplateId: String? = null,
    val viewportAspect: GuideViewportAspect = GuideViewportAspect.FOUR_TO_FIVE,
)

data class SceneGuideState(
    val observation: SceneObservation,
    val proposal: CompositionProposal,
    val layoutGuide: SceneLayoutGuide,
    val fixedLayout: FixedLayoutGuide? = null,
    val layoutState: GuideLayoutState = GuideLayoutState.Searching,
    val sceneSignature: SceneSignature? = null,
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
    private val templateSafetyMargin: Float = 0.05f,
    private val detectedSlotShapeConfig: DetectedSlotShapeConfig = DetectedSlotShapeConfig(),
) {
    private var layoutState: GuideLayoutState = GuideLayoutState.Searching
    private var manualTemplateId: String? = null
    private var fixedBaseTemplate: LayoutTemplate? = null
    private var fixedSource: LayoutSource? = null

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
        val referenceTemplate = LayoutTemplate.fromReference(
            id = "reference_${styleTarget.referenceSlots.hashCode()}",
            slots = styleTarget.referenceSlots,
            horizonY = styleTarget.horizonPosition,
            viewportAspect = signals.viewportAspect,
        )
        val explicitTemplate = signals.layoutTemplate
            ?: signals.layoutTemplateId?.let { LayoutTemplateCatalog.resolve(it, signals.viewportAspect) }
            ?: referenceTemplate
            ?: styleTarget.layoutTemplateId?.let { LayoutTemplateCatalog.resolve(it, signals.viewportAspect) }
        if (explicitTemplate != null && manualTemplateId == null) {
            manualTemplateId = explicitTemplate.id
            fixedBaseTemplate = explicitTemplate
            fixedSource = if (referenceTemplate != null && explicitTemplate.id == referenceTemplate.id) {
                LayoutSource.REFERENCE
            } else {
                LayoutSource.MANUAL
            }
            layoutState = GuideLayoutState.Fixed(
                GenericLayoutSynthesizer.transform(explicitTemplate, styleTarget, templateSafetyMargin),
                fixedSource ?: LayoutSource.MANUAL,
            )
        }
        val baseTemplate = when (val current = layoutState) {
            is GuideLayoutState.Fixed -> fixedBaseTemplate ?: current.template
            GuideLayoutState.Searching -> autoLayoutResolver.resolve(
                detections = observation.slotDetections,
                objectsFresh = observation.objectsFresh,
                styleTarget = styleTarget,
                viewportAspect = signals.viewportAspect,
            )?.let { selected ->
                // Capture only the confirmed scene's relative object shapes.
                // Subsequent frames use fixedBaseTemplate and cannot move or resize
                // the brackets until the user explicitly rescans.
                GenericLayoutSynthesizer.snapshotObjectShapes(
                    template = selected,
                    detections = observation.slotDetections,
                    config = detectedSlotShapeConfig,
                    safetyMargin = templateSafetyMargin,
                ).also { snapshot ->
                    fixedBaseTemplate = snapshot
                    layoutState = GuideLayoutState.Fixed(
                        GenericLayoutSynthesizer.transform(snapshot, styleTarget, templateSafetyMargin),
                        LayoutSource.AUTO,
                    )
                }
            }
        }
        val template = baseTemplate?.let { GenericLayoutSynthesizer.transform(it, styleTarget, templateSafetyMargin) }
        val fixedLayout = template?.let {
            FixedLayoutGuide(
                template = it,
                assignments = LayoutSlotAssigner.assign(it, observation.slotDetections),
            )
        }
        val layoutGuide = layoutGuideEngine.build(observation, proposal).copy(
            fixedLayout = fixedLayout,
        )
        return SceneGuideState(
            observation = observation,
            proposal = proposal,
            layoutGuide = layoutGuide,
            fixedLayout = fixedLayout,
            layoutState = layoutState,
            sceneSignature = observation.slotDetections.takeIf { it.isNotEmpty() }?.let { detections ->
                SceneSignature(
                    objectCount = detections.count { it.role == SlotRole.OBJECT },
                    hasPerson = detections.any { it.role == SlotRole.PERSON },
                    arrangement = GenericLayoutSynthesizer.chooseArrangement(
                        detections.filter { it.role == SlotRole.OBJECT },
                    ),
                    specialisedTemplateId = fixedBaseTemplate
                        ?.id
                        ?.takeIf { id -> id in setOf(LayoutTemplateCatalog.CAFE_TABLE, LayoutTemplateCatalog.DRINK_PAIR, LayoutTemplateCatalog.DRINK_TRIO, LayoutTemplateCatalog.STILL_LIFE) },
                    viewportAspect = signals.viewportAspect,
                )
            },
        )
    }

    fun selectManualLayout(templateId: String, styleTarget: StyleTarget = StyleTarget()): Boolean {
        val template = LayoutTemplateCatalog.resolve(templateId) ?: return false
        manualTemplateId = templateId
        autoLayoutResolver.reset()
        fixedBaseTemplate = template
        fixedSource = LayoutSource.MANUAL
        layoutState = GuideLayoutState.Fixed(
            GenericLayoutSynthesizer.transform(template, styleTarget, templateSafetyMargin),
            LayoutSource.MANUAL,
        )
        return true
    }

    fun rescan() {
        manualTemplateId = null
        fixedBaseTemplate = null
        fixedSource = null
        layoutState = GuideLayoutState.Searching
        autoLayoutResolver.reset()
    }

    fun updateStyle(styleTarget: StyleTarget) {
        val base = fixedBaseTemplate ?: return
        val source = fixedSource ?: if (manualTemplateId == null) LayoutSource.AUTO else LayoutSource.MANUAL
        layoutState = GuideLayoutState.Fixed(GenericLayoutSynthesizer.transform(base, styleTarget, templateSafetyMargin), source)
    }

    fun reset() {
        proposalEngine.reset()
        layoutGuideEngine.reset()
        autoLayoutResolver.reset()
        manualTemplateId = null
        fixedBaseTemplate = null
        fixedSource = null
        layoutState = GuideLayoutState.Searching
    }
}
