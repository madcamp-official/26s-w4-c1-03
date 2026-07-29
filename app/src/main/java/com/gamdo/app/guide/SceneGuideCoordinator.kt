// B 모듈 리드 승인 수정(remain_plan O-13, 2026-07-29)
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
    /** O-13 (2): how long the scene analyser is heard before a reference is shown. */
    private val referenceGraceFrames: Int = 8,
) {
    private var layoutState: GuideLayoutState = GuideLayoutState.Searching
    private var manualTemplateId: String? = null
    private var fixedBaseTemplate: LayoutTemplate? = null
    private var fixedSource: LayoutSource? = null

    /** Consecutive `update` calls spent Searching. Feeds [GuideCompositionChoice]. */
    private var framesWithoutScene: Int = 0

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
        // A genuine command: `selectManualLayout`, or a template the camera owner
        // passed down in the frame signals. The user asked for this layout by name,
        // so it latches without consulting anything.
        val explicitTemplate = signals.layoutTemplate
            ?: signals.layoutTemplateId?.let { LayoutTemplateCatalog.resolve(it, signals.viewportAspect) }
            ?: styleTarget.layoutTemplateId?.let { LayoutTemplateCatalog.resolve(it, signals.viewportAspect) }
        if (explicitTemplate != null && manualTemplateId == null) {
            manualTemplateId = explicitTemplate.id
            fixedBaseTemplate = explicitTemplate
            fixedSource = LayoutSource.MANUAL
            layoutState = GuideLayoutState.Fixed(
                GenericLayoutSynthesizer.transform(explicitTemplate, styleTarget, templateSafetyMargin),
                LayoutSource.MANUAL,
            )
        }
        val baseTemplate = when (val current = layoutState) {
            is GuideLayoutState.Fixed -> fixedBaseTemplate ?: current.template
            GuideLayoutState.Searching -> resolveSearching(
                observation = observation,
                styleTarget = styleTarget,
                signals = signals,
                referenceTemplate = referenceTemplate,
            )
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

    /**
     * O-13 (2) — one frame of "AI 추천 구도 vs 레퍼런스 구도", while Searching.
     *
     * ## What this replaced
     *
     * `referenceTemplate` used to sit in the `explicitTemplate` chain, one `?:` above
     * the preset's `layoutTemplateId`. So the instant a reference was selected it
     * latched `GuideLayoutState.Fixed(..., REFERENCE)` and `autoLayoutResolver` was
     * never consulted for that session — a three-cup reference over an empty desk drew
     * three brackets nobody could fill, and only 재탐색 could get out of it. That is a
     * command. O-13 says a reference's composition is a **candidate**.
     *
     * The decision now lives in [GuideCompositionChoice], which is pure and takes no
     * preset: swapping the rule is a one-file change with its own tests, which is what
     * the owner needs while the rule is still provisional.
     *
     * ## Ordering
     *
     * `autoLayoutResolver.resolve` runs **first**, on every Searching frame, exactly as
     * before. It is what "the AI has an opinion about this scene" means, and it has to
     * be asked before it can be weighed. Its own internal selection latch is harmless
     * when the reference then wins: nothing reads it again until [rescan], which resets
     * it.
     */
    private fun resolveSearching(
        observation: SceneObservation,
        styleTarget: StyleTarget,
        signals: SceneFrameSignals,
        referenceTemplate: LayoutTemplate?,
    ): LayoutTemplate? {
        val sceneTemplate = autoLayoutResolver.resolve(
            detections = observation.slotDetections,
            objectsFresh = observation.objectsFresh,
            styleTarget = styleTarget,
            viewportAspect = signals.viewportAspect,
        )
        val reliable = observation.slotDetections.filter { it.isReliable }
        val choice = GuideCompositionChoice.choose(
            reference = referenceTemplate?.let {
                ReferenceCompositionOffer(
                    personSlots = styleTarget.referenceSlots.count { slot -> slot.role == SlotRole.PERSON },
                    objectSlots = styleTarget.referenceSlots.count { slot -> slot.role == SlotRole.OBJECT },
                )
            },
            scene = SceneCompositionReading(
                confirmed = sceneTemplate != null,
                personDetections = reliable.count { it.role == SlotRole.PERSON },
                objectDetections = reliable.count { it.role == SlotRole.OBJECT },
            ),
            framesWithoutScene = framesWithoutScene,
            referenceGraceFrames = referenceGraceFrames,
        )
        framesWithoutScene++

        return when (choice) {
            GuideCompositionSource.NONE -> null

            GuideCompositionSource.REFERENCE -> referenceTemplate?.also { template ->
                // No `snapshotObjectShapes`: the reference's slot shapes are the
                // reference's, and reshaping them from the live detections would turn
                // "compose it like this photo" into "keep whatever you already have".
                manualTemplateId = template.id
                fixedBaseTemplate = template
                fixedSource = LayoutSource.REFERENCE
                framesWithoutScene = 0
                layoutState = GuideLayoutState.Fixed(
                    GenericLayoutSynthesizer.transform(template, styleTarget, templateSafetyMargin),
                    LayoutSource.REFERENCE,
                )
            }

            // Capture only the confirmed scene's relative object shapes. Subsequent
            // frames use fixedBaseTemplate and cannot move or resize the brackets
            // until the user explicitly rescans.
            GuideCompositionSource.SCENE -> sceneTemplate?.let { selected ->
                GenericLayoutSynthesizer.snapshotObjectShapes(
                    template = selected,
                    detections = observation.slotDetections,
                    config = detectedSlotShapeConfig,
                    safetyMargin = templateSafetyMargin,
                ).also { snapshot ->
                    fixedBaseTemplate = snapshot
                    framesWithoutScene = 0
                    layoutState = GuideLayoutState.Fixed(
                        GenericLayoutSynthesizer.transform(snapshot, styleTarget, templateSafetyMargin),
                        LayoutSource.AUTO,
                    )
                }
            }
        }
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
        framesWithoutScene = 0
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
        framesWithoutScene = 0
        layoutState = GuideLayoutState.Searching
    }
}
