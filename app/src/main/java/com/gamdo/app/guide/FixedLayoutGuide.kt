package com.gamdo.app.guide

import com.gamdo.app.detect.GuideObjectCategory
import com.gamdo.app.detect.NormalizedBox
import kotlin.math.abs
import kotlin.math.max

/** A screen-fixed target slot; its position never follows a detection. */
data class LayoutSlot(
    val id: String,
    val expectedCategory: GuideObjectCategory,
    val bounds: RectN,
    val required: Boolean = true,
    val centerTolerance: Float = 0.12f,
    val minimumOverlap: Float = 0.20f,
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
        require(opacity in 0f..0.6f)
        require(slots.map { it.id }.distinct().size == slots.size)
    }

    companion object {
        /** A useful multi-subject reference layout for the cafe example. */
        fun cafeTable(): LayoutTemplate = LayoutTemplate(
            id = "cafe_table_v1",
            horizonY = 0.62f,
            slots = listOf(
                LayoutSlot("cup_left", GuideObjectCategory.DRINKWARE, RectN(0.08f, 0.48f, 0.40f, 0.82f)),
                LayoutSlot("cup_right", GuideObjectCategory.DRINKWARE, RectN(0.60f, 0.48f, 0.92f, 0.82f)),
                LayoutSlot("cake_plate", GuideObjectCategory.FOOD_TABLEWARE, RectN(0.30f, 0.60f, 0.70f, 0.94f)),
            ),
        )
    }
}

data class SlotDetection(
    val id: String,
    val category: GuideObjectCategory,
    val bounds: NormalizedBox,
    val confidence: Float,
    val isReliable: Boolean = false,
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

enum class SlotMatchStatus { EMPTY, DETECTING, FILLED }

data class SlotMatch(
    val slotId: String,
    val status: SlotMatchStatus,
    val detectionId: String? = null,
    val overlap: Float = 0f,
    val centerError: Float = 1f,
)

data class FixedLayoutGuide(
    val template: LayoutTemplate,
    val matches: List<SlotMatch>,
) {
    val allRequiredFilled: Boolean
        get() = template.slots.filter { it.required }.all { slot ->
            matches.firstOrNull { it.slotId == slot.id }?.status == SlotMatchStatus.FILLED
        }
}

/** Matches detections to fixed slots. It never changes slot coordinates. */
class FixedLayoutSlotMatcher(
    private val confirmationWindow: Int = 5,
    private val confirmationsRequired: Int = 3,
    private val maxOccludedFrames: Int = 4,
) {
    private val history = mutableMapOf<String, ArrayDeque<Boolean>>()
    private val misses = mutableMapOf<String, Int>()
    private val filled = mutableMapOf<String, Boolean>()

    init {
        require(confirmationWindow >= 1)
        require(confirmationsRequired in 1..confirmationWindow)
        require(maxOccludedFrames >= 0)
    }

    fun match(template: LayoutTemplate, detections: List<SlotDetection>): FixedLayoutGuide {
        val available = detections.map { it.normalized() }.toMutableList()
        val matches = template.slots.map { slot ->
            val candidate = available
                .filter { it.isReliable && it.category == slot.expectedCategory && it.confidence >= 0.35f }
                .maxByOrNull { score(slot, it) }
            candidate?.let { available.remove(it) }

            val overlap = candidate?.let { overlap(slot.bounds, it.bounds) } ?: 0f
            val centerError = candidate?.let { centerError(slot.bounds, it.bounds) } ?: 1f
            val structurallyInside = candidate != null &&
                overlap >= slot.minimumOverlap && centerError <= slot.centerTolerance
            val frameHistory = history.getOrPut(slot.id) { ArrayDeque() }
            frameHistory.addLast(structurallyInside)
            while (frameHistory.size > confirmationWindow) frameHistory.removeFirst()
            val confirmed = frameHistory.count { it } >= confirmationsRequired
            if (confirmed) filled[slot.id] = true
            val wasFilled = filled[slot.id] == true && (misses[slot.id] ?: 0) < maxOccludedFrames
            if (structurallyInside) misses[slot.id] = 0 else misses[slot.id] = (misses[slot.id] ?: 0) + 1
            val status = when {
                (confirmed && structurallyInside) || wasFilled -> SlotMatchStatus.FILLED
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

/** Stable IDs shared between style selection and the rendering owner. */
object LayoutTemplateCatalog {
    const val PORTRAIT_PERSON = "portrait_person_v1"
    const val CAFE_TABLE = "cafe_table_v1"
    const val DRINK_PAIR = "drink_pair_v1"
    const val DRINK_TRIO = "drink_trio_v1"
    const val STILL_LIFE = "still_life_v1"

    fun resolve(id: String?): LayoutTemplate? = when (id) {
        PORTRAIT_PERSON -> LayoutTemplate(
            id = PORTRAIT_PERSON,
            slots = listOf(
                LayoutSlot(
                    id = "person",
                    expectedCategory = GuideObjectCategory.PERSON,
                    bounds = RectN(0.22f, 0.10f, 0.78f, 0.88f),
                    centerTolerance = 0.14f,
                ),
            ),
        )
        CAFE_TABLE -> LayoutTemplate.cafeTable()
        DRINK_PAIR -> LayoutTemplate(
            id = DRINK_PAIR,
            horizonY = 0.64f,
            slots = listOf(
                LayoutSlot("drink_left", GuideObjectCategory.DRINKWARE, RectN(0.10f, 0.48f, 0.42f, 0.84f)),
                LayoutSlot("drink_right", GuideObjectCategory.DRINKWARE, RectN(0.58f, 0.48f, 0.90f, 0.84f)),
            ),
        )
        DRINK_TRIO -> LayoutTemplate(
            id = DRINK_TRIO,
            horizonY = 0.64f,
            slots = listOf(
                LayoutSlot("drink_left", GuideObjectCategory.DRINKWARE, RectN(0.06f, 0.48f, 0.34f, 0.84f)),
                LayoutSlot("drink_center", GuideObjectCategory.DRINKWARE, RectN(0.36f, 0.42f, 0.64f, 0.80f)),
                LayoutSlot("drink_right", GuideObjectCategory.DRINKWARE, RectN(0.66f, 0.48f, 0.94f, 0.84f)),
            ),
        )
        STILL_LIFE -> LayoutTemplate(
            id = STILL_LIFE,
            horizonY = 0.58f,
            slots = listOf(
                LayoutSlot("main_plate", GuideObjectCategory.FOOD_TABLEWARE, RectN(0.25f, 0.48f, 0.75f, 0.90f)),
            ),
        )
        else -> null
    }
}

/**
 * Chooses a layout from the first stable scene, then freezes that choice for the
 * rest of the camera session. Detections after selection only fill slots; they
 * never cause the layout to chase or reconfigure around the objects.
 */
class AutoLayoutTemplateResolver(
    private val confirmationWindow: Int = 5,
    private val confirmationsRequired: Int = 3,
) {
    private val history = ArrayDeque<String?>()
    private var selectedId: String? = null

    fun resolve(
        detections: List<SlotDetection>,
        explicitId: String? = null,
    ): LayoutTemplate? {
        if (explicitId != null) {
            selectedId = explicitId
            history.clear()
            return LayoutTemplateCatalog.resolve(explicitId)
        }
        selectedId?.let { return LayoutTemplateCatalog.resolve(it) }
        val candidate = choose(detections)
        history.addLast(candidate)
        while (history.size > confirmationWindow) history.removeFirst()
        if (candidate != null && history.count { it == candidate } >= confirmationsRequired) {
            selectedId = candidate
        }
        return selectedId?.let(LayoutTemplateCatalog::resolve)
    }

    fun reset() {
        history.clear()
        selectedId = null
    }

    private fun choose(detections: List<SlotDetection>): String? {
        val drinks = detections.count { it.category == GuideObjectCategory.DRINKWARE }
        val food = detections.count { it.category == GuideObjectCategory.FOOD_TABLEWARE }
        return when {
            drinks >= 3 -> LayoutTemplateCatalog.DRINK_TRIO
            drinks >= 2 && food >= 1 -> LayoutTemplateCatalog.CAFE_TABLE
            drinks >= 2 -> LayoutTemplateCatalog.DRINK_PAIR
            detections.any { it.category == GuideObjectCategory.PERSON } -> LayoutTemplateCatalog.PORTRAIT_PERSON
            food >= 1 -> LayoutTemplateCatalog.STILL_LIFE
            else -> null
        }
    }
}
