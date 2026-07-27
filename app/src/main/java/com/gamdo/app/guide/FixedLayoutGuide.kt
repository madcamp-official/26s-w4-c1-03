package com.gamdo.app.guide

import com.gamdo.app.detect.GuideObjectCategory
import com.gamdo.app.detect.NormalizedBox
import kotlin.math.abs
import kotlin.math.max

enum class SlotRole { PERSON, OBJECT }

enum class SlotVisualKind { PERSON_SILHOUETTE, GENERIC_OBJECT, CUP, PLATE }

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
    val semanticHint: String? = null,
)

data class LayoutTemplate(
    val id: String,
    val slots: List<LayoutSlot>,
    val horizonY: Float? = null,
    val opacity: Float = 0.30f,
) {
    init {
        require(id.isNotBlank())
        require(slots.isNotEmpty())
        require(slots.size <= 4)
        require(opacity in 0f..0.6f)
        require(slots.map { it.id }.distinct().size == slots.size)
    }

    companion object {
        fun cafeTable(): LayoutTemplate = LayoutTemplate(
            id = "cafe_table_v1",
            horizonY = 0.62f,
            slots = listOf(
                LayoutSlot("cup_left", GuideObjectCategory.DRINKWARE, RectN(0.08f, 0.48f, 0.40f, 0.82f), visualKind = SlotVisualKind.CUP),
                LayoutSlot("cup_right", GuideObjectCategory.DRINKWARE, RectN(0.60f, 0.48f, 0.92f, 0.82f), visualKind = SlotVisualKind.CUP),
                LayoutSlot("cake_plate", GuideObjectCategory.FOOD_TABLEWARE, RectN(0.30f, 0.60f, 0.70f, 0.94f), visualKind = SlotVisualKind.PLATE),
            ),
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

@Deprecated("Product rendering no longer displays occupancy state; use LayoutTemplate directly.")
enum class SlotMatchStatus { EMPTY, DETECTING, FILLED }

@Deprecated("Product rendering no longer displays occupancy state; use LayoutTemplate directly.")
data class SlotMatch(
    val slotId: String,
    val status: SlotMatchStatus,
    val detectionId: String? = null,
    val overlap: Float = 0f,
    val centerError: Float = 1f,
)

data class FixedLayoutGuide(
    val template: LayoutTemplate,
    /** Debug/KPI only. CameraOverlay must render template slots uniformly. */
    val matches: List<SlotMatch> = emptyList(),
) {
    @Deprecated("Never use this value to decide whether the shutter works.")
    val allRequiredFilled: Boolean
        get() = template.slots.filter { it.required }.all { slot ->
            matches.firstOrNull { it.slotId == slot.id }?.status == SlotMatchStatus.FILLED
        }
}

/** Compatibility matcher used only by legacy KPI tests, never by product state. */
class FixedLayoutSlotMatcher(
    private val confirmationWindow: Int = 5,
    private val confirmationsRequired: Int = 3,
    private val maxOccludedFrames: Int = 4,
) {
    private val history = mutableMapOf<String, ArrayDeque<Boolean>>()
    private val misses = mutableMapOf<String, Int>()
    private val filled = mutableMapOf<String, Boolean>()

    fun match(template: LayoutTemplate, detections: List<SlotDetection>): FixedLayoutGuide {
        val available = detections.map { it.normalized() }.toMutableList()
        val matches = template.slots.map { slot ->
            val candidate = available
                .filter { it.isReliable && (slot.expectedCategory == null || it.category == slot.expectedCategory) }
                .maxByOrNull { score(slot, it) }
            candidate?.let { available.remove(it) }
            val overlap = candidate?.let { overlap(slot.bounds, it.bounds) } ?: 0f
            val centerError = candidate?.let { centerError(slot.bounds, it.bounds) } ?: 1f
            val inside = candidate != null && overlap >= slot.minimumOverlap && centerError <= slot.centerTolerance
            val frameHistory = history.getOrPut(slot.id) { ArrayDeque() }
            frameHistory.addLast(inside)
            while (frameHistory.size > confirmationWindow) frameHistory.removeFirst()
            val confirmed = frameHistory.count { it } >= confirmationsRequired
            if (confirmed) filled[slot.id] = true
            val wasFilled = filled[slot.id] == true && (misses[slot.id] ?: 0) < maxOccludedFrames
            if (inside) misses[slot.id] = 0 else misses[slot.id] = (misses[slot.id] ?: 0) + 1
            val status = when {
                (confirmed && inside) || wasFilled -> SlotMatchStatus.FILLED
                candidate != null -> SlotMatchStatus.DETECTING
                else -> SlotMatchStatus.EMPTY
            }
            SlotMatch(slot.id, status, candidate?.id, overlap, centerError)
        }
        return FixedLayoutGuide(template, matches)
    }

    fun reset() {
        history.clear()
        misses.clear()
        filled.clear()
    }

    private fun score(slot: LayoutSlot, detection: SlotDetection): Float =
        overlap(slot.bounds, detection.bounds) * 0.7f +
            (1f - centerError(slot.bounds, detection.bounds)).coerceIn(0f, 1f) * 0.3f

    private fun centerError(slot: RectN, detection: NormalizedBox): Float =
        max(abs(slot.centerX - detection.centerX), abs(slot.centerY - detection.centerY))

    private fun overlap(slot: RectN, detection: NormalizedBox): Float {
        val left = max(slot.left, detection.left)
        val top = max(slot.top, detection.top)
        val right = minOf(slot.right, detection.right)
        val bottom = minOf(slot.bottom, detection.bottom)
        val intersection = ((right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f))
        val slotArea = (slot.width * slot.height).coerceAtLeast(0.0001f)
        return (intersection / slotArea).coerceIn(0f, 1f)
    }

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

    val manualIds = listOf(
        PORTRAIT_PERSON, CAFE_TABLE, DRINK_PAIR, DRINK_TRIO, STILL_LIFE,
        GENERIC_SINGLE, GENERIC_PAIR, GENERIC_TRIO, GENERIC_QUAD,
    )

    fun resolve(id: String?): LayoutTemplate? = when (id) {
        PORTRAIT_PERSON -> LayoutTemplate(id, listOf(LayoutSlot("person", GuideObjectCategory.PERSON, RectN(0.22f, 0.10f, 0.78f, 0.88f), role = SlotRole.PERSON, visualKind = SlotVisualKind.PERSON_SILHOUETTE)))
        CAFE_TABLE -> LayoutTemplate.cafeTable()
        DRINK_PAIR -> LayoutTemplate(id, listOf(
            LayoutSlot("drink_left", GuideObjectCategory.DRINKWARE, RectN(0.10f, 0.48f, 0.42f, 0.84f), visualKind = SlotVisualKind.CUP),
            LayoutSlot("drink_right", GuideObjectCategory.DRINKWARE, RectN(0.58f, 0.48f, 0.90f, 0.84f), visualKind = SlotVisualKind.CUP),
        ))
        DRINK_TRIO -> LayoutTemplate(id, listOf(
            LayoutSlot("drink_left", GuideObjectCategory.DRINKWARE, RectN(0.06f, 0.48f, 0.34f, 0.84f), visualKind = SlotVisualKind.CUP),
            LayoutSlot("drink_center", GuideObjectCategory.DRINKWARE, RectN(0.36f, 0.42f, 0.64f, 0.80f), visualKind = SlotVisualKind.CUP),
            LayoutSlot("drink_right", GuideObjectCategory.DRINKWARE, RectN(0.66f, 0.48f, 0.94f, 0.84f), visualKind = SlotVisualKind.CUP),
        ))
        STILL_LIFE -> LayoutTemplate(id, listOf(LayoutSlot("main_plate", GuideObjectCategory.FOOD_TABLEWARE, RectN(0.25f, 0.48f, 0.75f, 0.90f), visualKind = SlotVisualKind.PLATE)))
        GENERIC_SINGLE -> GenericLayoutSynthesizer.generic(1, Arrangement.SINGLE)
        GENERIC_PAIR -> GenericLayoutSynthesizer.generic(2, Arrangement.ROW)
        GENERIC_TRIO -> GenericLayoutSynthesizer.generic(3, Arrangement.TRIANGLE)
        GENERIC_QUAD -> GenericLayoutSynthesizer.generic(4, Arrangement.GRID)
        else -> null
    }
}

enum class LayoutSource { AUTO, MANUAL }

sealed interface GuideLayoutState {
    data object Searching : GuideLayoutState
    data class Fixed(val template: LayoutTemplate, val source: LayoutSource) : GuideLayoutState
}

data class LayoutTemplateSummary(val id: String, val slotCount: Int, val displayName: String)

enum class Arrangement { SINGLE, ROW, COLUMN, DIAGONAL, TRIANGLE, GRID }

object GenericLayoutSynthesizer {
    fun chooseArrangement(detections: List<SlotDetection>): Arrangement {
        if (detections.size <= 1) return Arrangement.SINGLE
        val xSpan = detections.maxOf { it.bounds.centerX } - detections.minOf { it.bounds.centerX }
        val ySpan = detections.maxOf { it.bounds.centerY } - detections.minOf { it.bounds.centerY }
        return when {
            xSpan >= ySpan * 1.5f -> Arrangement.ROW
            ySpan >= xSpan * 1.5f -> Arrangement.COLUMN
            detections.size == 2 -> Arrangement.DIAGONAL
            detections.size == 3 -> Arrangement.TRIANGLE
            else -> Arrangement.GRID
        }
    }

    fun generic(count: Int, arrangement: Arrangement, id: String = "generic_${count}_$arrangement"): LayoutTemplate {
        val positions = when (arrangement) {
            Arrangement.SINGLE -> listOf(RectN(0.34f, 0.30f, 0.66f, 0.70f))
            Arrangement.ROW -> (0 until count).map { index -> slotRect(index, count, horizontal = true) }
            Arrangement.COLUMN -> (0 until count).map { index -> slotRect(index, count, horizontal = false) }
            Arrangement.DIAGONAL -> listOf(RectN(0.18f, 0.28f, 0.48f, 0.62f), RectN(0.52f, 0.42f, 0.82f, 0.76f))
            Arrangement.TRIANGLE -> listOf(RectN(0.18f, 0.42f, 0.46f, 0.76f), RectN(0.54f, 0.42f, 0.82f, 0.76f), RectN(0.36f, 0.14f, 0.64f, 0.48f))
            Arrangement.GRID -> listOf(RectN(0.16f, 0.18f, 0.46f, 0.48f), RectN(0.54f, 0.18f, 0.84f, 0.48f), RectN(0.16f, 0.54f, 0.46f, 0.84f), RectN(0.54f, 0.54f, 0.84f, 0.84f)).take(count)
        }
        return LayoutTemplate(
            id = id,
            slots = positions.take(4).mapIndexed { index, bounds ->
                LayoutSlot("object_$index", null, bounds, role = SlotRole.OBJECT, visualKind = SlotVisualKind.GENERIC_OBJECT)
            },
        )
    }

    fun transform(template: LayoutTemplate, style: StyleTarget): LayoutTemplate {
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
            RectN(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f).clamped().let { next -> slot.copy(bounds = next) }
        })
    }

    private fun slotRect(index: Int, count: Int, horizontal: Boolean): RectN {
        val size = if (count <= 2) 0.32f else 0.25f
        val gap = if (count <= 2) 0.36f else 0.27f
        val center = if (horizontal) 0.5f + (index - (count - 1) / 2f) * gap else 0.5f
        val vertical = if (horizontal) 0.55f else 0.5f + (index - (count - 1) / 2f) * gap
        return RectN(center - size / 2f, vertical - size / 2f, center + size / 2f, vertical + size / 2f)
    }
}

class AutoLayoutTemplateResolver(
    private val confirmationWindow: Int = 5,
    private val confirmationsRequired: Int = 3,
) {
    private val history = ArrayDeque<String>()
    private var selectedId: String? = null
    private var selectedTemplate: LayoutTemplate? = null

    fun resolve(
        detections: List<SlotDetection>,
        objectsFresh: Boolean = true,
        explicitId: String? = null,
        styleTarget: StyleTarget = StyleTarget(),
    ): LayoutTemplate? {
        explicitId?.let {
            selectedId = it
            history.clear()
            selectedTemplate = LayoutTemplateCatalog.resolve(it)
            return selectedTemplate
        }
        selectedTemplate?.let { template ->
            return template
        }
        if (!objectsFresh) return null
        val candidate = choose(detections)
        if (candidate == null) {
            history.clear()
            return null
        }
        history.addLast(candidate.id)
        while (history.size > confirmationWindow) history.removeFirst()
        if (history.count { it == candidate.id } >= confirmationsRequired) {
            selectedId = candidate.id
            selectedTemplate = candidate.template
        }
        return selectedTemplate
    }

    fun reset() {
        history.clear()
        selectedId = null
        selectedTemplate = null
    }

    private data class Candidate(val id: String, val template: LayoutTemplate)

    private fun choose(detections: List<SlotDetection>): Candidate? {
        val valid = detections.filter { it.isReliable }.take(4)
        if (valid.isEmpty()) return null
        val person = valid.firstOrNull { it.role == SlotRole.PERSON }
        val objects = valid.filter { it.role == SlotRole.OBJECT }
        val drinks = objects.count { it.category == GuideObjectCategory.DRINKWARE && (it.semanticConfidence ?: 0f) >= 0.65f }
        val food = objects.count { it.category == GuideObjectCategory.FOOD_TABLEWARE && (it.semanticConfidence ?: 0f) >= 0.65f }
        if (person != null && objects.isEmpty()) {
            return Candidate(LayoutTemplateCatalog.PORTRAIT_PERSON, LayoutTemplateCatalog.resolve(LayoutTemplateCatalog.PORTRAIT_PERSON)!!)
        }
        if (drinks >= 3) return Candidate(LayoutTemplateCatalog.DRINK_TRIO, LayoutTemplateCatalog.resolve(LayoutTemplateCatalog.DRINK_TRIO)!!)
        if (drinks >= 2 && food >= 1) return Candidate(LayoutTemplateCatalog.CAFE_TABLE, LayoutTemplateCatalog.resolve(LayoutTemplateCatalog.CAFE_TABLE)!!)
        if (drinks >= 2) return Candidate(LayoutTemplateCatalog.DRINK_PAIR, LayoutTemplateCatalog.resolve(LayoutTemplateCatalog.DRINK_PAIR)!!)

        val base = GenericLayoutSynthesizer.generic(
            count = objects.size + if (person != null) 1 else 0,
            arrangement = GenericLayoutSynthesizer.chooseArrangement(valid),
            id = "auto_${valid.size}_${GenericLayoutSynthesizer.chooseArrangement(valid).name.lowercase()}",
        )
        return Candidate(base.id, if (person == null) base else withPerson(base, person))
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
        )
    }
}
