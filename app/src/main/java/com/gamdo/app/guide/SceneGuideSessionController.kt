package com.gamdo.app.guide

import com.gamdo.app.detect.DetectionResult
import com.gamdo.app.detect.FaceObservation
import com.gamdo.app.detect.GuideObjectCategory
import com.gamdo.app.detect.NormalizedBox
import com.gamdo.app.detect.ObjectObservation
import com.gamdo.app.detect.ObjectDetectionBatch
import com.gamdo.app.detect.StableSceneTracker
import com.gamdo.app.detect.SceneSearchScopeStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** P1-facing session seam for automatic and manual fixed-layout control. */
class SceneGuideSessionController(
    private val coordinator: SceneGuideCoordinator = SceneGuideCoordinator(),
    private val tracker: StableSceneTracker = StableSceneTracker(),
    initialScopeStore: SceneSearchScopeStore? = null,
    private val sceneModeClassifier: SceneModeClassifier = HeuristicSceneModeClassifier(),
    private val sceneTechniqueSelector: SceneTechniqueSelector = SceneTechniqueSelector(),
) {
    private var scopeStore: SceneSearchScopeStore? = initialScopeStore
    private val _layoutState = MutableStateFlow<GuideLayoutState>(GuideLayoutState.Searching)
    val layoutState: StateFlow<GuideLayoutState> = _layoutState.asStateFlow()

    private val _searchScope = MutableStateFlow<SceneSearchScope>(SceneSearchScope.Default)
    val searchScope: StateFlow<SceneSearchScope> = _searchScope.asStateFlow()

    private val _sceneModeDecision = MutableStateFlow<SceneModeDecision?>(null)
    /** The proposed or user-selected situation. P1 may render this as a mode chip. */
    val sceneModeDecision: StateFlow<SceneModeDecision?> = _sceneModeDecision.asStateFlow()

    private val _guideMarks = MutableStateFlow<SceneGuideMarks?>(null)
    /** Fixed dots/rings/silhouette contract; never contains raw detector geometry. */
    val guideMarks: StateFlow<SceneGuideMarks?> = _guideMarks.asStateFlow()

    private var userSceneMode: CaptureSceneMode? = null

    val availableManualLayouts: List<LayoutTemplateSummary> = LayoutTemplateCatalog.manualSummaries

    fun attachScopeStore(store: SceneSearchScopeStore) { scopeStore = store }

    fun updateScene(
        batch: ObjectDetectionBatch,
        faces: List<FaceObservation>,
        style: StyleTarget,
    ): SceneGuideState {
        return updateScene(
            detection = DetectionResult(
                faces = faces,
                pose = null,
                objects = batch.objects,
                objectsFresh = batch.isFresh,
                objectSequenceId = batch.sequenceId,
            ),
            styleTarget = style,
        )
    }

    /**
     * The only product path that confirms a camera scene. Faces join the same
     * tracker as objects, so a one-frame face result cannot bypass the 3/5
     * contract and immediately freeze a portrait layout.
     */
    fun updateScene(
        detection: DetectionResult,
        styleTarget: StyleTarget,
        signals: SceneFrameSignals = SceneFrameSignals(),
    ): SceneGuideState {
        val largestFace = detection.faces.maxByOrNull { it.box.width * it.box.height }
        val trackedCandidates = buildList {
            largestFace?.let { face ->
                add(
                    ObjectObservation(
                        box = face.box,
                        detectionConfidence = 1f,
                        category = GuideObjectCategory.PERSON,
                    ),
                )
            }
            // Object observations may be a cached result while the face
            // detector has produced a new frame. Do not count cached objects
            // again as fresh evidence; the current face can still participate
            // in portrait confirmation independently.
            if (detection.objectsFresh) addAll(detection.objects)
        }
        val trackerFresh = detection.objectsFresh || largestFace != null
        val stable = tracker.accept(
            ObjectDetectionBatch(
                objects = trackedCandidates,
                isFresh = trackerFresh,
                sequenceId = detection.objectSequenceId,
            ),
        )
        val stablePerson = stable.firstOrNull { it.category == GuideObjectCategory.PERSON }
        val stableFace = stablePerson?.let { person ->
            largestFace?.takeIf { face -> overlap(face.box, person.box) >= 0.30f }
                ?: FaceObservation(person.box, null, null, 0f)
        }
        val state = coordinator.update(
            detection = detection.copy(
                faces = listOfNotNull(stableFace),
                pose = detection.pose?.takeIf { stableFace != null },
                objects = stable.filter { it.category != GuideObjectCategory.PERSON },
                // The resolver only needs to know whether this *stable scene*
                // update is new. A fresh face is valid portrait evidence even
                // when the object detector is returning a cached batch.
                objectsFresh = trackerFresh,
            ),
            styleTarget = styleTarget,
            signals = signals,
        )
        updateSituationAndMarks(
            detection = detection,
            stable = stable,
            state = state,
            style = styleTarget,
            signals = signals,
        )
        _layoutState.value = state.layoutState
        return state
    }

    /** Selects a situation and invalidates the previous fixed scene. AUTO clears the override. */
    fun selectSceneMode(mode: CaptureSceneMode) {
        userSceneMode = mode.takeUnless { it == CaptureSceneMode.AUTO }
        resetSearchState()
    }

    /** Returns the session to classifier-driven AUTO. */
    fun resetSceneMode() {
        userSceneMode = null
        resetSearchState()
    }

    fun selectManualLayout(templateId: String, style: StyleTarget = StyleTarget()): Boolean {
        val selected = coordinator.selectManualLayout(templateId, style)
        if (selected) {
            _layoutState.value = coordinator.currentLayoutState
        }
        return selected
    }

    fun rescan() {
        tracker.reset()
        coordinator.rescan()
        _searchScope.value = SceneSearchScope.Default
        scopeStore?.setDefault()
        _layoutState.value = GuideLayoutState.Searching
    }

    /** Resets automatic search around a normalized preview anchor. */
    fun rescanAt(anchorX: Float, anchorY: Float) {
        tracker.rescanAt(anchorX, anchorY)
        coordinator.rescan()
        _searchScope.value = SceneSearchScope.Tap(PointN(anchorX, anchorY).clamped())
        scopeStore?.setTap(PointN(anchorX, anchorY).clamped())
        _layoutState.value = GuideLayoutState.Searching
    }

    /** Restricts a fresh automatic search to a lasso drawn in PreviewView pixels. */
    fun rescanInPolygon(points: List<Pair<Float, Float>>, geometry: PreviewGeometry): Boolean {
        val polygon = ScenePolygonRegion.fromViewPath(points, geometry) ?: return false
        tracker.rescanInPolygon(polygon)
        coordinator.rescan()
        _searchScope.value = SceneSearchScope.Polygon(polygon.points)
        scopeStore?.setPolygon(polygon)
        _layoutState.value = GuideLayoutState.Searching
        return true
    }

    fun rescanInNormalizedPolygon(points: List<PointN>): Boolean {
        val polygon = ScenePolygonRegion.fromNormalized(points) ?: return false
        tracker.rescanInPolygon(polygon)
        coordinator.rescan()
        _searchScope.value = SceneSearchScope.Polygon(polygon.points)
        scopeStore?.setPolygon(polygon)
        _layoutState.value = GuideLayoutState.Searching
        return true
    }

    fun cancelPolygonSearch() {
        tracker.cancelPolygonSearch()
        coordinator.rescan()
        _searchScope.value = SceneSearchScope.Default
        scopeStore?.setDefault()
        _layoutState.value = GuideLayoutState.Searching
    }

    fun updateStyle(style: StyleTarget) {
        coordinator.updateStyle(style)
        _layoutState.value = coordinator.currentLayoutState
    }

    fun endSession() {
        tracker.reset()
        coordinator.reset()
        userSceneMode = null
        _sceneModeDecision.value = null
        _guideMarks.value = null
        _searchScope.value = SceneSearchScope.Default
        scopeStore?.setDefault()
        _layoutState.value = GuideLayoutState.Searching
    }

    private fun resetSearchState() {
        tracker.reset()
        coordinator.rescan()
        _sceneModeDecision.value = userSceneMode?.let {
            SceneModeDecision(it, 1f, SceneModeSource.USER)
        }
        _guideMarks.value = null
        _layoutState.value = GuideLayoutState.Searching
    }

    private fun updateSituationAndMarks(
        detection: DetectionResult,
        stable: List<ObjectObservation>,
        state: SceneGuideState,
        style: StyleTarget,
        signals: SceneFrameSignals,
    ) {
        val stablePeople = stable.count { it.category == GuideObjectCategory.PERSON }
        val stableObjects = stable.count { it.category != GuideObjectCategory.PERSON }
        val evidence = SceneModeEvidence(
            faces = detection.faces.size,
            people = stablePeople,
            objects = stableObjects,
            backgroundRatio = signals.backgroundRatio ?: style.backgroundRatioRange?.let { (it.start + it.endInclusive) / 2f },
            horizonY = signals.horizonY,
            tiltDeg = null,
            sceneSymmetry = signals.sceneSymmetry,
        )
        val decision = userSceneMode?.let { SceneModeDecision(it, 1f, SceneModeSource.USER) }
            ?: sceneModeClassifier.classify(evidence)
        _sceneModeDecision.value = decision
        if (_guideMarks.value == null && decision != null) {
            val marks = sceneTechniqueSelector.select(
                mode = decision.suggested,
                detections = state.observation.slotDetections,
                evidence = evidence,
                style = style,
            )
            if (marks != null && state.layoutState is GuideLayoutState.Fixed) {
                _guideMarks.value = marks
            }
        }
    }

    private fun overlap(a: NormalizedBox, b: NormalizedBox): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        val intersection = (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)
        val union = a.width * a.height + b.width * b.height - intersection
        return if (union <= 0f) 0f else intersection / union
    }
}
