package com.gamdo.app.guide

import com.gamdo.app.detect.DetectionResult
import com.gamdo.app.detect.FaceObservation
import com.gamdo.app.detect.ObjectDetectionBatch
import com.gamdo.app.detect.StableSceneTracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** P1-facing session seam for automatic and manual fixed-layout control. */
class SceneGuideSessionController(
    private val coordinator: SceneGuideCoordinator = SceneGuideCoordinator(),
) {
    private val tracker = StableSceneTracker()
    private val _layoutState = MutableStateFlow<GuideLayoutState>(GuideLayoutState.Searching)
    val layoutState: StateFlow<GuideLayoutState> = _layoutState.asStateFlow()

    val availableManualLayouts: List<LayoutTemplateSummary> = LayoutTemplateCatalog.manualIds.mapNotNull { id ->
        LayoutTemplateCatalog.resolve(id)?.let { template ->
            LayoutTemplateSummary(
                id = id,
                slotCount = template.slots.size,
                displayName = when (id) {
                    LayoutTemplateCatalog.PORTRAIT_PERSON -> "인물"
                    LayoutTemplateCatalog.CAFE_TABLE -> "카페 테이블"
                    LayoutTemplateCatalog.DRINK_PAIR -> "음료 2개"
                    LayoutTemplateCatalog.DRINK_TRIO -> "음료 3개"
                    LayoutTemplateCatalog.STILL_LIFE -> "정물"
                    LayoutTemplateCatalog.GENERIC_SINGLE -> "물체 1개"
                    LayoutTemplateCatalog.GENERIC_PAIR -> "물체 2개"
                    LayoutTemplateCatalog.GENERIC_TRIO -> "물체 3개"
                    else -> "물체 4개"
                },
            )
        }
    }

    fun updateScene(
        batch: ObjectDetectionBatch,
        faces: List<FaceObservation>,
        style: StyleTarget,
    ): SceneGuideState {
        val stableObjects = tracker.accept(batch)
        val state = coordinator.update(
            detection = DetectionResult(
                faces = faces,
                pose = null,
                objects = stableObjects,
                objectsFresh = batch.isFresh,
                objectSequenceId = batch.sequenceId,
            ),
            styleTarget = style,
        )
        _layoutState.value = state.layoutState
        return state
    }

    fun selectManualLayout(templateId: String, style: StyleTarget = StyleTarget()): Boolean {
        val selected = coordinator.selectManualLayout(templateId, style)
        if (selected) _layoutState.value = coordinator.currentLayoutState
        return selected
    }

    fun rescan() {
        tracker.reset()
        coordinator.rescan()
        _layoutState.value = GuideLayoutState.Searching
    }

    fun updateStyle(style: StyleTarget) {
        val current = _layoutState.value
        if (current is GuideLayoutState.Fixed) {
            val transformed = GenericLayoutSynthesizer.transform(current.template, style)
            _layoutState.value = current.copy(template = transformed)
        }
    }

    fun endSession() {
        tracker.reset()
        coordinator.reset()
        _layoutState.value = GuideLayoutState.Searching
    }
}
