package com.gamdo.app.guide

import com.gamdo.app.detect.GuideObjectCategory
import com.gamdo.app.detect.NormalizedBox
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

enum class SlotRole { PERSON, OBJECT }

enum class SlotVisualKind { PERSON_SILHOUETTE, PERSON_BRACKET, GENERIC_OBJECT, CUP, PLATE }

/** Server-derived, normalized slot used only when a reference is active. */
data class ReferenceTargetSlot(
    val role: SlotRole,
    val visualKind: SlotVisualKind,
    val bounds: RectN,
    val semanticHint: String? = null,
)

/** The two capture ratios supported by GAMDO's camera contract. */
enum class GuideViewportAspect { FOUR_TO_FIVE, ONE_TO_ONE }

/** A screen-fixed slot. Its coordinates are never updated from live detections. */
data class LayoutSlot(
    val id: String,
    val expectedCategory: GuideObjectCategory? = null,
    val bounds: RectN,
    val required: Boolean = true,
    val centerTolerance: Float = 0.12f,
    val minimumOverlap: Float = 0.20f,
    val role: SlotRole = if (expectedCategory == GuideObjectCategory.PERSON) SlotRole.PERSON else SlotRole.OBJECT,
    val visualKind: SlotVisualKind = when (expectedCategory) {
        GuideObjectCategory.PERSON -> SlotVisualKind.PERSON_SILHOUETTE
        GuideObjectCategory.DRINKWARE -> SlotVisualKind.CUP
        GuideObjectCategory.FOOD_TABLEWARE -> SlotVisualKind.PLATE
        else -> SlotVisualKind.GENERIC_OBJECT
    },
    /** Rendering hint only; it never changes the composition decision. */
    val preferredAspectRatio: Float = when (visualKind) {
        SlotVisualKind.PERSON_SILHOUETTE -> 0.52f
        SlotVisualKind.PERSON_BRACKET -> 0.52f
        SlotVisualKind.CUP -> 0.78f
        SlotVisualKind.PLATE -> 1.15f
        SlotVisualKind.GENERIC_OBJECT -> 1f
    },
    val semanticHint: String? = null,
)

data class LayoutTemplate(
    val id: String,
    val slots: List<LayoutSlot>,
    val horizonY: Float? = null,
    val opacity: Float = 0.30f,
    val viewportAspect: GuideViewportAspect = GuideViewportAspect.FOUR_TO_FIVE,
    val poseGuide: PoseGuideTemplate? = null,
) {
    init {
        require(id.isNotBlank())
        require(slots.isNotEmpty())
        require(slots.size <= 4)
        require(opacity in 0f..0.6f)
        require(slots.map { it.id }.distinct().size == slots.size)
    }

    companion object {
        fun fromReference(
            id: String,
            slots: List<ReferenceTargetSlot>,
            horizonY: Float? = null,
            viewportAspect: GuideViewportAspect = GuideViewportAspect.FOUR_TO_FIVE,
            opacity: Float = 0.30f,
        ): LayoutTemplate? {
            val normalized = slots.take(4).mapIndexed { index, slot ->
                LayoutSlot(
                    id = "reference_${index + 1}",
                    expectedCategory = null,
                    bounds = slot.bounds.clamped(),
                    role = slot.role,
                    visualKind = slot.visualKind,
                    preferredAspectRatio = slot.bounds.width / slot.bounds.height.coerceAtLeast(0.01f),
                    semanticHint = slot.semanticHint,
                )
            }
            return normalized.takeIf { it.isNotEmpty() }?.let {
                LayoutTemplate(id, it, horizonY, opacity, viewportAspect)
            }
        }

        fun cafeTable(viewportAspect: GuideViewportAspect = GuideViewportAspect.FOUR_TO_FIVE): LayoutTemplate = LayoutTemplate(
            id = "cafe_table_v1",
            horizonY = 0.62f,
            slots = listOf(
                LayoutSlot("cup_left", GuideObjectCategory.DRINKWARE, RectN(0.08f, 0.48f, 0.40f, 0.82f), visualKind = SlotVisualKind.CUP),
                LayoutSlot("cup_right", GuideObjectCategory.DRINKWARE, RectN(0.60f, 0.48f, 0.92f, 0.82f), visualKind = SlotVisualKind.CUP),
                LayoutSlot("cake_plate", GuideObjectCategory.FOOD_TABLEWARE, RectN(0.30f, 0.60f, 0.70f, 0.94f), visualKind = SlotVisualKind.PLATE),
            ),
            viewportAspect = viewportAspect,
        )
    }
}

/** Internal-only diagnostics retained for KPI/debug compatibility. */
data class SlotDetection(
    val id: String,
    val category: GuideObjectCategory,
    val bounds: NormalizedBox,
    val confidence: Float,
    val isReliable: Boolean = false,
    val role: SlotRole = if (category == GuideObjectCategory.PERSON) SlotRole.PERSON else SlotRole.OBJECT,
    val visualKind: SlotVisualKind = SlotVisualKind.GENERIC_OBJECT,
    val semanticConfidence: Float? = null,
    val semanticConfirmed: Boolean = false,
    val outline: List<LayoutGuidePoint> = emptyList(),
    val stableObjectKey: String = id,
) {
    fun normalized() = copy(
        bounds = NormalizedBox(
            bounds.left.coerceIn(0f, 1f),
            bounds.top.coerceIn(0f, 1f),
            bounds.right.coerceIn(bounds.left.coerceIn(0f, 1f), 1f),
            bounds.bottom.coerceIn(bounds.top.coerceIn(0f, 1f), 1f),
        ),
        confidence = confidence.coerceIn(0f, 1f),
    )
}

/**
 * A latched layout template plus the per-frame detection→slot correspondence.
 *
 * **There is no occupancy state here and there must not be one.** D2 forbids
 * showing a slot as filled or empty. `SlotMatchStatus`, `SlotMatch`,
 * `FixedLayoutSlotMatcher` and `allRequiredFilled` lived here, unrendered by
 * either branch of the 2026-07-28 merge, and carried three live bugs — overlap
 * normalized by slot area alone (a full-frame box scored 1.0), greedy assignment
 * with no score floor, and a `filled` flag that was never cleared. The fix for a
 * display we are not allowed to draw is deletion, not repair. `git log` has them
 * if occupancy is ever re-specified.
 */
data class FixedLayoutGuide(
    val template: LayoutTemplate,
    /** Detection-to-slot correspondence for downstream KPI and rendering seams. */
    val assignments: List<LayoutSlotAssignment> = emptyList(),
)

data class LayoutSlotAssignment(
    val slotId: String,
    val detectionId: String?,
    val overlap: Float,
    val centerDistance: Float,
)

/** Exact one-to-one correspondence for the small (maximum four-slot) scene. */
object LayoutSlotAssigner {
    fun assign(template: LayoutTemplate, detections: List<SlotDetection>): List<LayoutSlotAssignment> {
        val candidates = detections.filter { it.isReliable }.take(4)
        val pairs = bestAssignment(template.slots, candidates)
        return template.slots.mapIndexed { index, slot ->
            val candidate = pairs.getOrNull(index)
            LayoutSlotAssignment(slot.id, candidate?.id, candidate?.let { overlap(slot.bounds, it.bounds) } ?: 0f, candidate?.let { centerDistance(slot.bounds, it.bounds) } ?: 1f)
        }
    }

    private fun bestAssignment(slots: List<LayoutSlot>, detections: List<SlotDetection>): List<SlotDetection?> {
        if (slots.isEmpty() || detections.isEmpty()) return List(slots.size) { null }
        var bestCost = Float.POSITIVE_INFINITY
        var best = List<SlotDetection?>(slots.size) { null }
        fun visit(slotIndex: Int, used: Set<Int>, current: List<SlotDetection?>, cost: Float) {
            if (slotIndex == slots.size) {
                if (cost < bestCost) { bestCost = cost; best = current }
                return
            }
            visit(slotIndex + 1, used, current + null, cost + 0.75f)
            detections.indices.filterNot { it in used }.filter { detectionIndex ->
                val detection = detections[detectionIndex]
                slots[slotIndex].expectedCategory == null ||
                    detection.category == slots[slotIndex].expectedCategory ||
                    detection.category == GuideObjectCategory.UNKNOWN
            }.forEach { detectionIndex ->
                val detection = detections[detectionIndex]
                visit(slotIndex + 1, used + detectionIndex, current + detection, cost + cost(slots[slotIndex], detection))
            }
        }
        visit(0, emptySet(), emptyList(), 0f)
        return best
    }

    private fun cost(slot: LayoutSlot, detection: SlotDetection): Float {
        val center = centerDistance(slot.bounds, detection.bounds).coerceIn(0f, 1f)
        val targetAspect = slot.preferredAspectRatio.coerceAtLeast(0.01f)
        val sourceAspect = (detection.bounds.width / detection.bounds.height.coerceAtLeast(0.01f)).coerceIn(0.4f, 2.5f)
        val aspect = (kotlin.math.abs(kotlin.math.ln(targetAspect / sourceAspect)) / 2.0f).coerceIn(0f, 1f)
        val slotArea = (slot.bounds.width * slot.bounds.height).coerceAtLeast(0.001f)
        val detectionArea = (detection.bounds.width * detection.bounds.height).coerceAtLeast(0.001f)
        val areaRank = (kotlin.math.abs(kotlin.math.ln(slotArea / detectionArea)) / 3.0f).coerceIn(0f, 1f)
        val semantic = if (slot.expectedCategory == null || detection.category == slot.expectedCategory || detection.category == GuideObjectCategory.UNKNOWN) 0f else 1f
        return center * 0.55f + aspect * 0.20f + areaRank * 0.15f + semantic * 0.10f
    }

    private fun score(slot: RectN, detection: NormalizedBox): Float =
        overlap(slot, detection) * 0.7f +
            (1f - centerDistance(slot, detection)).coerceIn(0f, 1f) * 0.3f

    private fun overlap(slot: RectN, detection: NormalizedBox): Float {
        val left = maxOf(slot.left, detection.left)
        val top = maxOf(slot.top, detection.top)
        val right = minOf(slot.right, detection.right)
        val bottom = minOf(slot.bottom, detection.bottom)
        val intersection = ((right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f))
        return (intersection / (slot.width * slot.height).coerceAtLeast(0.0001f)).coerceIn(0f, 1f)
    }

    private fun centerDistance(slot: RectN, detection: NormalizedBox): Float =
        maxOf(abs(slot.centerX - detection.centerX), abs(slot.centerY - detection.centerY))

    private val RectN.centerX get() = (left + right) / 2f
    private val RectN.centerY get() = (top + bottom) / 2f
}

object LayoutTemplateCatalog {
    const val PORTRAIT_PERSON = "portrait_person_v1"
    const val CAFE_TABLE = "cafe_table_v1"
    const val DRINK_PAIR = "drink_pair_v1"
    const val DRINK_TRIO = "drink_trio_v1"
    const val STILL_LIFE = "still_life_v1"
    const val GENERIC_SINGLE = "generic_single_v1"
    const val GENERIC_PAIR = "generic_pair_v1"
    const val GENERIC_TRIO = "generic_trio_v1"
    const val GENERIC_QUAD = "generic_quad_v1"

    const val PERSON_FULL_CENTER = "person_full_center_v2"
    const val PERSON_FULL_OFFSET = "person_full_offset_v2"
    const val PERSON_FULL_RELAXED = "person_full_relaxed_v3"
    const val PERSON_FULL_WALKING = "person_full_walking_v3"
    const val PERSON_UPPER = "person_upper_v2"
    const val PERSON_SEATED = "person_seated_v2"
    const val PERSON_OBJECT = "person_object_v2"
    const val OBJECT_SINGLE = "object_single_v2"
    const val OBJECT_PAIR_BALANCED = "object_pair_balanced_v2"
    const val OBJECT_PAIR_DIAGONAL = "object_pair_diagonal_v2"
    const val OBJECT_TRIO_TRIANGLE = "object_trio_triangle_v2"
    const val OBJECT_TRIO_ROW = "object_trio_row_v2"
    const val OBJECT_QUAD_GRID = "object_quad_grid_v2"
    const val OBJECT_QUAD_HIERARCHY = "object_quad_hierarchy_v3"
    const val CAFE_TABLE_V2 = "cafe_table_v2"

    val manualIds = listOf(
        PERSON_FULL_CENTER, PERSON_FULL_OFFSET, PERSON_FULL_RELAXED, PERSON_FULL_WALKING,
        PERSON_UPPER, PERSON_SEATED,
        OBJECT_SINGLE, OBJECT_PAIR_BALANCED, OBJECT_PAIR_DIAGONAL,
        OBJECT_TRIO_TRIANGLE, OBJECT_QUAD_HIERARCHY, CAFE_TABLE_V2,
    )

    val legacyIds = listOf(
        PORTRAIT_PERSON, CAFE_TABLE, DRINK_PAIR, DRINK_TRIO, STILL_LIFE,
        GENERIC_SINGLE, GENERIC_PAIR, GENERIC_TRIO, GENERIC_QUAD,
    )

    val manualSummaries: List<LayoutTemplateSummary> by lazy {
        manualIds.mapNotNull { id ->
            resolve(id)?.let { template ->
                LayoutTemplateSummary(
                    id = id,
                    displayName = displayName(id),
                    category = if (template.slots.any { it.role == SlotRole.PERSON }) LayoutCategory.PERSON else LayoutCategory.OBJECT,
                    slots = template.slots.map { LayoutPreviewSlot(it.role, it.visualKind, it.bounds) },
                    // Kept nullable for persisted-client compatibility. New
                    // templates do not expose pose landmarks or a skeleton.
                    poseTemplateId = null,
                )
            }
        }
    }

    fun resolve(
        id: String?,
        viewportAspect: GuideViewportAspect = GuideViewportAspect.FOUR_TO_FIVE,
    ): LayoutTemplate? = when (id) {
        PORTRAIT_PERSON -> LayoutTemplate(id, listOf(LayoutSlot("person", GuideObjectCategory.PERSON, RectN(0.25f, 0.14f, 0.75f, 0.86f), role = SlotRole.PERSON, visualKind = SlotVisualKind.PERSON_SILHOUETTE)), viewportAspect = viewportAspect)
        PERSON_FULL_CENTER -> portraitFrame(id, RectN(0.30f, 0.08f, 0.70f, 0.94f), viewportAspect)
        PERSON_FULL_OFFSET -> portraitFrame(id, RectN(0.15f, 0.08f, 0.53f, 0.94f), viewportAspect)
        PERSON_FULL_RELAXED -> portraitFrame(id, RectN(0.27f, 0.08f, 0.72f, 0.94f), viewportAspect)
        PERSON_FULL_WALKING -> portraitFrame(id, RectN(0.14f, 0.08f, 0.55f, 0.94f), viewportAspect)
        PERSON_UPPER -> portraitFrame(id, RectN(0.30f, 0.17f, 0.78f, 0.92f), viewportAspect)
        PERSON_SEATED -> portraitFrame(id, RectN(0.20f, 0.15f, 0.80f, 0.90f), viewportAspect)
        PERSON_OBJECT -> LayoutTemplate(id, listOf(
            LayoutSlot("person", GuideObjectCategory.PERSON, RectN(0.08f, 0.14f, 0.50f, 0.88f), role = SlotRole.PERSON, visualKind = SlotVisualKind.PERSON_SILHOUETTE),
            LayoutSlot("object", null, RectN(0.60f, 0.56f, 0.84f, 0.78f)),
        ), viewportAspect = viewportAspect)
        CAFE_TABLE, CAFE_TABLE_V2 -> LayoutTemplate.cafeTable(viewportAspect).copy(id = id)
        DRINK_PAIR -> LayoutTemplate(id, listOf(
            LayoutSlot("drink_left", GuideObjectCategory.DRINKWARE, RectN(0.10f, 0.48f, 0.42f, 0.84f), visualKind = SlotVisualKind.CUP),
            LayoutSlot("drink_right", GuideObjectCategory.DRINKWARE, RectN(0.58f, 0.48f, 0.90f, 0.84f), visualKind = SlotVisualKind.CUP),
        ), viewportAspect = viewportAspect)
        DRINK_TRIO -> LayoutTemplate(id, listOf(
            LayoutSlot("drink_left", GuideObjectCategory.DRINKWARE, RectN(0.06f, 0.48f, 0.34f, 0.84f), visualKind = SlotVisualKind.CUP),
            LayoutSlot("drink_center", GuideObjectCategory.DRINKWARE, RectN(0.36f, 0.42f, 0.64f, 0.80f), visualKind = SlotVisualKind.CUP),
            LayoutSlot("drink_right", GuideObjectCategory.DRINKWARE, RectN(0.66f, 0.48f, 0.94f, 0.84f), visualKind = SlotVisualKind.CUP),
        ), viewportAspect = viewportAspect)
        STILL_LIFE -> LayoutTemplate(id, listOf(LayoutSlot("main_plate", GuideObjectCategory.FOOD_TABLEWARE, RectN(0.29f, 0.52f, 0.71f, 0.86f), visualKind = SlotVisualKind.PLATE)), viewportAspect = viewportAspect)
        GENERIC_SINGLE, OBJECT_SINGLE -> GenericLayoutSynthesizer.generic(1, Arrangement.SINGLE, id = id, viewportAspect = viewportAspect)
        GENERIC_PAIR, OBJECT_PAIR_BALANCED -> GenericLayoutSynthesizer.generic(2, Arrangement.ROW, id = id, viewportAspect = viewportAspect)
        OBJECT_PAIR_DIAGONAL -> GenericLayoutSynthesizer.generic(2, Arrangement.DIAGONAL, id = id, viewportAspect = viewportAspect)
        GENERIC_TRIO, OBJECT_TRIO_TRIANGLE -> GenericLayoutSynthesizer.generic(3, Arrangement.TRIANGLE, id = id, viewportAspect = viewportAspect)
        OBJECT_TRIO_ROW -> GenericLayoutSynthesizer.generic(3, Arrangement.TRIANGLE, id = id, viewportAspect = viewportAspect)
        GENERIC_QUAD, OBJECT_QUAD_GRID -> GenericLayoutSynthesizer.generic(4, Arrangement.DIAMOND, id = id, viewportAspect = viewportAspect)
        OBJECT_QUAD_HIERARCHY -> GenericLayoutSynthesizer.generic(4, Arrangement.DIAMOND, id = id, viewportAspect = viewportAspect)
        else -> null
    }

    private fun portraitFrame(id: String, bounds: RectN, viewportAspect: GuideViewportAspect) = LayoutTemplate(
        id = id,
        slots = listOf(LayoutSlot("person", GuideObjectCategory.PERSON, bounds, role = SlotRole.PERSON, visualKind = SlotVisualKind.PERSON_BRACKET)),
        viewportAspect = viewportAspect,
    )

    private fun displayName(id: String): String = when (id) {
        PERSON_FULL_CENTER -> "전신 중앙"
        PERSON_FULL_OFFSET -> "전신 비대칭"
        PERSON_FULL_RELAXED -> "전신 자연스러운 흐름"
        PERSON_FULL_WALKING -> "전신 리드 룸"
        PERSON_UPPER -> "상반신"
        PERSON_SEATED -> "앉은 인물"
        PERSON_OBJECT -> "인물과 소품"
        OBJECT_SINGLE -> "물체 1개"
        OBJECT_PAIR_BALANCED -> "물체 2개 균형"
        OBJECT_PAIR_DIAGONAL -> "물체 2개 대각"
        OBJECT_TRIO_TRIANGLE -> "물체 3개 삼각"
        OBJECT_TRIO_ROW -> "물체 3개 연속"
        OBJECT_QUAD_GRID -> "물체 4개 2×2"
        CAFE_TABLE_V2 -> "카페 테이블"
        else -> id
    }
}

enum class LayoutSource { AUTO, MANUAL, REFERENCE }

sealed interface GuideLayoutState {
    data object Searching : GuideLayoutState
    /** A fixed state is renderable by definition; an empty template must remain Searching. */
    data class Fixed(val template: LayoutTemplate, val source: LayoutSource) : GuideLayoutState {
        init {
            require(template.id.isNotBlank()) { "Fixed layout requires a template id" }
            require(template.slots.isNotEmpty()) { "Fixed layout requires at least one slot" }
        }
    }
}

enum class LayoutCategory { PERSON, OBJECT }

data class LayoutPreviewSlot(val role: SlotRole, val visualKind: SlotVisualKind, val bounds: RectN)

data class LayoutTemplateSummary(
    val id: String,
    val displayName: String,
    val category: LayoutCategory,
    val slots: List<LayoutPreviewSlot>,
    val poseTemplateId: String? = null,
) {
    val slotCount: Int get() = slots.size
}

enum class Arrangement { SINGLE, ROW, COLUMN, DIAGONAL, TRIANGLE, DIAMOND, GRID }

/** Privacy-safe scene decision trace: no pixels or coordinates are retained. */
data class SceneSignature(
    val objectCount: Int,
    val hasPerson: Boolean,
    val arrangement: Arrangement,
    val specialisedTemplateId: String? = null,
    val viewportAspect: GuideViewportAspect = GuideViewportAspect.FOUR_TO_FIVE,
)

/**
 * Bounds for the one-time shape snapshot taken when an automatic layout locks.
 *
 * These values deliberately constrain the detector's noisy box measurements:
 * they preserve the meaningful difference between a tall cup and a wide plate,
 * without turning the composition guide into a live object-tracking overlay.
 */
data class DetectedSlotShapeConfig(
    val aspectRatioMin: Float = 0.55f,
    val aspectRatioMax: Float = 1.80f,
    val scaleMin: Float = 0.70f,
    val scaleMax: Float = 1.16f,
    val referenceAreaRatio: Float = 0.08f,
) {
    init {
        require(aspectRatioMin > 0f)
        require(aspectRatioMax >= aspectRatioMin)
        require(scaleMin > 0f)
        require(scaleMax >= scaleMin)
        require(referenceAreaRatio in 0.001f..1f)
    }
}

object GenericLayoutSynthesizer {
    fun chooseArrangement(detections: List<SlotDetection>): Arrangement {
        if (detections.size <= 1) return Arrangement.SINGLE
        val xSpan = detections.maxOf { it.bounds.centerX } - detections.minOf { it.bounds.centerX }
        val ySpan = detections.maxOf { it.bounds.centerY } - detections.minOf { it.bounds.centerY }
        return when {
            detections.size == 2 && xSpan >= ySpan * 1.5f -> Arrangement.ROW
            detections.size == 2 -> Arrangement.DIAGONAL
            detections.size == 3 -> Arrangement.TRIANGLE
            detections.size >= 4 -> Arrangement.DIAMOND
            else -> Arrangement.SINGLE
        }
    }

    fun generic(
        count: Int,
        arrangement: Arrangement,
        id: String = "generic_${count}_$arrangement",
        viewportAspect: GuideViewportAspect = GuideViewportAspect.FOUR_TO_FIVE,
    ): LayoutTemplate {
        val positions = when {
            arrangement != Arrangement.COLUMN && arrangement != Arrangement.GRID ->
                CompositionTechniqueCatalog.forObjects(count, arrangement).slots.map { it.bounds }
            else -> when (arrangement) {
            // Generic objects are guides to place the subject, not large
            // portrait frames. Keep them compact so the camera scene remains
            // visible around the layout.
            Arrangement.SINGLE -> listOf(RectN(0.39f, 0.43f, 0.61f, 0.65f))
            Arrangement.ROW -> (0 until count).map { index -> slotRect(index, count, horizontal = true, viewportAspect = viewportAspect) }
            Arrangement.COLUMN -> (0 until count).map { index -> slotRect(index, count, horizontal = false, viewportAspect = viewportAspect) }
            Arrangement.DIAGONAL -> listOf(RectN(0.26f, 0.38f, 0.46f, 0.62f), RectN(0.54f, 0.44f, 0.74f, 0.68f))
            Arrangement.TRIANGLE -> listOf(RectN(0.25f, 0.50f, 0.45f, 0.74f), RectN(0.55f, 0.50f, 0.75f, 0.74f), RectN(0.40f, 0.25f, 0.60f, 0.49f))
            Arrangement.DIAMOND -> listOf(
                RectN(0.40f, 0.18f, 0.60f, 0.38f),
                RectN(0.18f, 0.42f, 0.38f, 0.62f),
                RectN(0.62f, 0.42f, 0.82f, 0.62f),
                RectN(0.40f, 0.66f, 0.60f, 0.86f),
            )
            Arrangement.GRID -> listOf(RectN(0.24f, 0.26f, 0.44f, 0.48f), RectN(0.56f, 0.26f, 0.76f, 0.48f), RectN(0.24f, 0.56f, 0.44f, 0.78f), RectN(0.56f, 0.56f, 0.76f, 0.78f)).take(count)
            }
        }
        return LayoutTemplate(
            id = id,
            slots = positions.take(4).mapIndexed { index, bounds ->
                LayoutSlot("object_$index", null, bounds, role = SlotRole.OBJECT, visualKind = SlotVisualKind.GENERIC_OBJECT)
            },
            viewportAspect = viewportAspect,
        )
    }

    /**
     * Applies the *first stable scene's* object proportions to a template.
     *
     * Detection positions continue to choose a layout family only. This function
     * changes each object slot's aspect ratio and relative visual weight once,
     * before [transform] applies the selected GAMDO style. Later live detections
     * never call it again, so the resulting brackets remain fixed for the session.
     */
    fun snapshotObjectShapes(
        template: LayoutTemplate,
        detections: List<SlotDetection>,
        config: DetectedSlotShapeConfig = DetectedSlotShapeConfig(),
        safetyMargin: Float = 0.05f,
    ): LayoutTemplate {
        val objectSlots = template.slots.withIndex().filter { (_, slot) -> slot.role == SlotRole.OBJECT }
        val objects = detections.filter { it.isReliable && it.role == SlotRole.OBJECT }
        if (objectSlots.isEmpty() || objects.isEmpty()) return template

        val matches = matchObjectSlotsExact(objectSlots, objects)
        if (matches.isEmpty()) return template
        val medianArea = matches.map { (_, detection) -> detection.bounds.width * detection.bounds.height }
            .sorted()
            .let { areas ->
                if (areas.size % 2 == 0) (areas[areas.size / 2 - 1] + areas[areas.size / 2]) / 2f
                else areas[areas.size / 2]
            }
            .coerceAtLeast(0.0001f)
        val shaped = template.slots.toMutableList()
        matches.forEach { (slotIndex, detection) ->
            val slot = shaped[slotIndex]
            val detectedArea = (detection.bounds.width * detection.bounds.height).coerceAtLeast(0.0001f)
            val aspect = (detection.bounds.width / detection.bounds.height.coerceAtLeast(0.0001f))
                .coerceIn(config.aspectRatioMin, config.aspectRatioMax)
            // Blend scene scale with within-scene scale. The blend keeps a single
            // small object compact while still making a cake visibly larger than
            // a neighbouring cup when both were stable at lock time.
            val sceneScale = sqrt(detectedArea / config.referenceAreaRatio)
            val relativeScale = sqrt(detectedArea / medianArea)
            val scale = (sceneScale * 0.60f + relativeScale * 0.40f)
                .coerceIn(config.scaleMin, config.scaleMax)
            val baseAspect = (slot.bounds.width / slot.bounds.height.coerceAtLeast(0.0001f))
            val width = slot.bounds.width * scale * sqrt(aspect / baseAspect)
            val height = slot.bounds.height * scale * sqrt(baseAspect / aspect)
            val bounds = RectN(
                slot.bounds.centerX - width / 2f,
                slot.bounds.centerY - height / 2f,
                slot.bounds.centerX + width / 2f,
                slot.bounds.centerY + height / 2f,
            ).withinSafetyMargin(safetyMargin)
            shaped[slotIndex] = slot.copy(bounds = bounds, preferredAspectRatio = aspect)
        }
        return template.copy(slots = shaped)
    }

    fun transform(
        template: LayoutTemplate,
        style: StyleTarget,
        safetyMargin: Float = 0.05f,
    ): LayoutTemplate {
        val baseScale = ((style.subjectScaleRange.start + style.subjectScaleRange.endInclusive) / 2f / 0.45f)
            .coerceIn(0.85f, 1.15f)
        val background = style.backgroundRatioRange?.let { (it.start + it.endInclusive) / 2f } ?: 0.5f
        val spacing = (0.8f + background * 0.4f).coerceIn(0.9f, 1.15f)
        val centerX = style.subjectAnchorX.coerceIn(0.2f, 0.8f)
        val centerY = style.subjectAnchorY.coerceIn(0.2f, 0.8f)
        val oldCenterX = template.slots.map { (it.bounds.left + it.bounds.right) / 2f }.average().toFloat()
        val oldCenterY = template.slots.map { (it.bounds.top + it.bounds.bottom) / 2f }.average().toFloat()
        return template.copy(slots = template.slots.map { slot ->
            val b = slot.bounds
            val cx = centerX + ((b.left + b.right) / 2f - oldCenterX) * spacing
            val cy = centerY + ((b.top + b.bottom) / 2f - oldCenterY) * spacing
            val w = b.width * baseScale
            val h = b.height * baseScale
            RectN(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f).withinSafetyMargin(safetyMargin).let { next -> slot.copy(bounds = next) }
        })
    }

    private fun slotRect(index: Int, count: Int, horizontal: Boolean, viewportAspect: GuideViewportAspect): RectN {
        val size = if (count <= 2) 0.22f else 0.19f
        val gap = if (count <= 2) 0.30f else 0.23f
        val center = if (horizontal) 0.5f + (index - (count - 1) / 2f) * gap else 0.5f
        val baseY = if (viewportAspect == GuideViewportAspect.FOUR_TO_FIVE) 0.58f else 0.54f
        val vertical = if (horizontal) baseY else 0.5f + (index - (count - 1) / 2f) * gap
        return RectN(center - size / 2f, vertical - size / 2f, center + size / 2f, vertical + size / 2f)
    }

    /** Uses the same exact assignment as the public KPI mapping for shape snapshots. */
    private fun matchObjectSlotsExact(
        slots: List<IndexedValue<LayoutSlot>>,
        detections: List<SlotDetection>,
    ): List<Pair<Int, SlotDetection>> {
        val matchingTemplate = LayoutTemplate("shape_matching", slots.map { it.value })
        val byId = detections.associateBy { it.id }
        return LayoutSlotAssigner.assign(matchingTemplate, detections).mapNotNull { assignment ->
            val detection = assignment.detectionId?.let(byId::get) ?: return@mapNotNull null
            val slotIndex = slots.firstOrNull { it.value.id == assignment.slotId }?.index ?: return@mapNotNull null
            slotIndex to detection
        }
    }

    /** Legacy normalized-center matcher retained only for old serialized fixtures. */
    private fun matchObjectSlots(
        slots: List<IndexedValue<LayoutSlot>>,
        detections: List<SlotDetection>,
    ): List<Pair<Int, SlotDetection>> {
        val selectedDetections = detections.take(slots.size)
        val slotCenters = slots.map { it.value.bounds.centerX to it.value.bounds.centerY }
        val objectCenters = selectedDetections.map { it.bounds.centerX to it.bounds.centerY }
        val slotXSpan = slotCenters.maxOf { it.first } - slotCenters.minOf { it.first }
        val slotYSpan = slotCenters.maxOf { it.second } - slotCenters.minOf { it.second }
        val objectXSpan = objectCenters.maxOf { it.first } - objectCenters.minOf { it.first }
        val objectYSpan = objectCenters.maxOf { it.second } - objectCenters.minOf { it.second }
        val useX = slotXSpan >= 0.04f && objectXSpan >= 0.04f
        val useY = slotYSpan >= 0.04f && objectYSpan >= 0.04f
        val slotMinX = slotCenters.minOf { it.first }
        val slotMinY = slotCenters.minOf { it.second }
        val objectMinX = objectCenters.minOf { it.first }
        val objectMinY = objectCenters.minOf { it.second }

        fun normalizedX(value: Float, min: Float, span: Float): Float = if (span < 0.04f) 0.5f else (value - min) / span
        fun normalizedY(value: Float, min: Float, span: Float): Float = if (span < 0.04f) 0.5f else (value - min) / span

        val pairs = slots.flatMap { slot ->
            selectedDetections.map { detection ->
                val target = slot.value.bounds
                val dx = if (useX) normalizedX(target.centerX, slotMinX, slotXSpan) - normalizedX(detection.bounds.centerX, objectMinX, objectXSpan) else 0f
                val dy = if (useY) normalizedY(target.centerY, slotMinY, slotYSpan) - normalizedY(detection.bounds.centerY, objectMinY, objectYSpan) else 0f
                Triple(dx * dx + dy * dy, slot, detection)
            }
        }.sortedBy { it.first }
        val usedSlots = mutableSetOf<Int>()
        val usedDetections = mutableSetOf<String>()
        return buildList {
            pairs.forEach { (_, slot, detection) ->
                if (slot.index !in usedSlots && detection.id !in usedDetections) {
                    add(slot.index to detection)
                    usedSlots += slot.index
                    usedDetections += detection.id
                }
            }
        }
    }

    private fun RectN.withinSafetyMargin(margin: Float = 0.05f): RectN {
        val width = width.coerceAtMost(1f - margin * 2f)
        val height = height.coerceAtMost(1f - margin * 2f)
        val centerX = ((left + right) / 2f).coerceIn(margin + width / 2f, 1f - margin - width / 2f)
        val centerY = ((top + bottom) / 2f).coerceIn(margin + height / 2f, 1f - margin - height / 2f)
        return RectN(centerX - width / 2f, centerY - height / 2f, centerX + width / 2f, centerY + height / 2f)
    }

    private val RectN.centerX: Float get() = (left + right) / 2f
    private val RectN.centerY: Float get() = (top + bottom) / 2f
}

class AutoLayoutTemplateResolver {
    private var selectedId: String? = null
    private var selectedTemplate: LayoutTemplate? = null

    fun resolve(
        detections: List<SlotDetection>,
        objectsFresh: Boolean = true,
        explicitId: String? = null,
        styleTarget: StyleTarget = StyleTarget(),
        viewportAspect: GuideViewportAspect = GuideViewportAspect.FOUR_TO_FIVE,
    ): LayoutTemplate? {
        explicitId?.let {
            selectedId = it
            selectedTemplate = LayoutTemplateCatalog.resolve(it, viewportAspect)
            return selectedTemplate
        }
        selectedTemplate?.let { template ->
            return template
        }
        if (!objectsFresh) return null
        val candidate = choose(detections, viewportAspect)
        if (candidate == null) {
            return null
        }
        // StableSceneTracker is the single 3/5 confirmation owner. A second
        // history here delays the first user-visible layout for no benefit.
        selectedId = candidate.id
        selectedTemplate = candidate.template
        return selectedTemplate
    }

    fun reset() {
        selectedId = null
        selectedTemplate = null
    }

    private data class Candidate(val id: String, val template: LayoutTemplate)

    private fun choose(detections: List<SlotDetection>, viewportAspect: GuideViewportAspect): Candidate? {
        val valid = detections.filter { it.isReliable }.take(4)
        if (valid.isEmpty()) return null
        val person = valid.firstOrNull { it.role == SlotRole.PERSON }
        val objects = valid.filter { it.role == SlotRole.OBJECT }
        val drinks = objects.count { it.category == GuideObjectCategory.DRINKWARE && it.semanticConfirmed && (it.semanticConfidence ?: 0f) >= 0.80f }
        val food = objects.count { it.category == GuideObjectCategory.FOOD_TABLEWARE && it.semanticConfirmed && (it.semanticConfidence ?: 0f) >= 0.80f }
        if (person != null && objects.isEmpty()) {
            return Candidate(LayoutTemplateCatalog.PORTRAIT_PERSON, LayoutTemplateCatalog.resolve(LayoutTemplateCatalog.PORTRAIT_PERSON, viewportAspect)!!)
        }
        if (drinks >= 3) return specialisedCandidate(LayoutTemplateCatalog.DRINK_TRIO, person, viewportAspect)
        if (drinks >= 2 && food >= 1) return specialisedCandidate(LayoutTemplateCatalog.CAFE_TABLE, person, viewportAspect)
        if (drinks >= 2) return specialisedCandidate(LayoutTemplateCatalog.DRINK_PAIR, person, viewportAspect)

        val objectCount = objects.size.coerceAtMost(if (person == null) 4 else 3)
        if (objectCount == 0) return null
        val base = GenericLayoutSynthesizer.generic(
            count = objectCount,
            arrangement = GenericLayoutSynthesizer.chooseArrangement(objects),
            id = "auto_${objectCount}_${GenericLayoutSynthesizer.chooseArrangement(objects).name.lowercase()}",
            viewportAspect = viewportAspect,
        )
        return Candidate(base.id, if (person == null) base else withPerson(base, person))
    }

    private fun specialisedCandidate(
        id: String,
        person: SlotDetection?,
        viewportAspect: GuideViewportAspect,
    ): Candidate {
        val base = LayoutTemplateCatalog.resolve(id, viewportAspect)!!
        return Candidate(id, if (person == null) base else withPerson(base, person))
    }

    private fun withPerson(base: LayoutTemplate, person: SlotDetection): LayoutTemplate {
        val personOnLeft = person.bounds.centerX < base.slots.map { (it.bounds.left + it.bounds.right) / 2f }.average().toFloat()
        val personSlot = LayoutSlot(
            id = "person",
            expectedCategory = GuideObjectCategory.PERSON,
            bounds = if (personOnLeft) RectN(0.06f, 0.18f, 0.38f, 0.82f) else RectN(0.62f, 0.18f, 0.94f, 0.82f),
            role = SlotRole.PERSON,
            visualKind = SlotVisualKind.PERSON_SILHOUETTE,
        )
        return LayoutTemplate(
            id = "auto_person_${base.slots.size}",
            slots = (listOf(personSlot) + base.slots).take(4),
            horizonY = base.horizonY,
            viewportAspect = base.viewportAspect,
        )
    }
}
