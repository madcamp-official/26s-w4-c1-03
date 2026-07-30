package com.gamdo.app.guide

import com.gamdo.app.detect.GuideObjectCategory

/** Photography techniques exposed by the V3 guide catalog. */
enum class CompositionTechnique {
    CENTERED,
    RULE_OF_THIRDS,
    BALANCE,
    DIAGONAL,
    TRIANGLE,
    HIERARCHY,
    SYMMETRY,
    ENVIRONMENTAL_PORTRAIT,
}

data class CompositionTemplateV3(
    val id: String,
    val technique: CompositionTechnique,
    val slots: List<LayoutSlot>,
)

/** Fixed, bounded composition library used by automatic object layouts. */
object CompositionTechniqueCatalog {
    fun forObjects(count: Int, arrangement: Arrangement): CompositionTemplateV3 {
        val (technique, centers) = when {
            count <= 1 -> CompositionTechnique.CENTERED to listOf(0.50f to 0.55f)
            count == 2 && arrangement == Arrangement.ROW -> CompositionTechnique.BALANCE to listOf(0.33f to 0.56f, 0.67f to 0.56f)
            count == 2 -> CompositionTechnique.DIAGONAL to listOf(0.32f to 0.67f, 0.68f to 0.38f)
            count == 3 -> CompositionTechnique.TRIANGLE to listOf(0.50f to 0.35f, 0.31f to 0.67f, 0.69f to 0.67f)
            else -> CompositionTechnique.HIERARCHY to listOf(0.50f to 0.28f, 0.28f to 0.52f, 0.72f to 0.52f, 0.50f to 0.76f)
        }
        val areas = when {
            count <= 1 -> listOf(0.10f)
            count == 2 -> List(2) { 0.085f }
            count == 3 -> listOf(0.10f, 0.075f, 0.075f)
            else -> listOf(0.10f, 0.065f, 0.065f, 0.065f)
        }
        val slots = centers.take(count.coerceIn(1, 4)).mapIndexed { index, (x, y) ->
            val area = areas[index]
            val size = kotlin.math.sqrt(area)
            LayoutSlot("object_$index", bounds = RectN(x - size / 2f, y - size / 2f, x + size / 2f, y + size / 2f))
        }
        return CompositionTemplateV3("objects_${count}_${technique.name.lowercase()}", technique, slots)
    }
}

enum class PortraitCoverage { FACE_ONLY, UPPER_BODY, FULL_BODY }

enum class PortraitSilhouetteKind {
    FULL_CENTER,
    FULL_RELAXED,
    FULL_WALKING,
    UPPER_BODY,
    SEATED,
    ENVIRONMENTAL,
}

data class PortraitFramingTemplate(
    val id: String,
    val displayName: String,
    val personBounds: RectN,
    val faceSafeArea: RectN?,
    val propSlots: List<LayoutSlot> = emptyList(),
    val mirrorable: Boolean,
    val silhouetteKind: PortraitSilhouetteKind,
)

data class PortraitSceneEvidence(
    val coverage: PortraitCoverage,
    val faceBounds: RectN,
    val personBounds: RectN?,
    val faceDirection: HorizontalDirection? = null,
    val openSpaceLeft: Float = 0.5f,
    val openSpaceRight: Float = 0.5f,
    val objectCount: Int = 0,
    val backgroundRatio: Float = 0.5f,
    val sceneSymmetry: Float = 0f,
    val preferredTemplateId: String? = null,
)

enum class HorizontalDirection { LEFT, RIGHT }

object PortraitFramingCatalog {
    val fullCenter = PortraitFramingTemplate(
        "portrait_full_center_v3", "전신 중앙", RectN(0.30f, 0.08f, 0.70f, 0.94f),
        RectN(0.42f, 0.10f, 0.58f, 0.27f), mirrorable = false,
        silhouetteKind = PortraitSilhouetteKind.FULL_CENTER,
    )
    val fullThirds = PortraitFramingTemplate(
        "portrait_full_thirds_v3", "전신 삼분할", RectN(0.15f, 0.08f, 0.53f, 0.94f),
        RectN(0.27f, 0.10f, 0.43f, 0.27f), mirrorable = true,
        silhouetteKind = PortraitSilhouetteKind.FULL_RELAXED,
    )
    val fullWalking = PortraitFramingTemplate(
        "portrait_full_lead_room_v3", "전신 리드 룸", RectN(0.14f, 0.08f, 0.55f, 0.94f),
        RectN(0.26f, 0.10f, 0.42f, 0.27f), mirrorable = true,
        silhouetteKind = PortraitSilhouetteKind.FULL_WALKING,
    )
    val upper = PortraitFramingTemplate(
        "portrait_upper_45_v3", "상반신", RectN(0.30f, 0.17f, 0.78f, 0.92f),
        RectN(0.48f, 0.22f, 0.68f, 0.40f), mirrorable = true,
        silhouetteKind = PortraitSilhouetteKind.UPPER_BODY,
    )
    val seated = PortraitFramingTemplate(
        "portrait_seated_v3", "앉은 인물", RectN(0.20f, 0.15f, 0.80f, 0.90f),
        RectN(0.42f, 0.17f, 0.58f, 0.34f), mirrorable = true,
        silhouetteKind = PortraitSilhouetteKind.SEATED,
    )
    val environmental = PortraitFramingTemplate(
        "portrait_environmental_v3", "환경 인물", RectN(0.14f, 0.14f, 0.52f, 0.90f),
        RectN(0.27f, 0.17f, 0.42f, 0.31f), mirrorable = true,
        silhouetteKind = PortraitSilhouetteKind.ENVIRONMENTAL,
    )

    val manual = listOf(fullCenter, fullThirds, fullWalking, upper, seated, environmental)

    fun select(evidence: PortraitSceneEvidence, preferredId: String? = null): PortraitFramingTemplate {
        manual.firstOrNull { it.id == preferredId }?.let { return it }
        if (evidence.coverage == PortraitCoverage.FACE_ONLY) return upper
        if (evidence.objectCount > 0) return fullThirds
        if (evidence.backgroundRatio >= 0.45f) return environmental
        if (evidence.coverage == PortraitCoverage.FULL_BODY && evidence.sceneSymmetry >= 0.70f) return fullCenter
        if (evidence.coverage == PortraitCoverage.FULL_BODY) return fullThirds
        return upper
    }
}

/** Converts face/person boxes into the small amount of evidence needed by V3. */
object PortraitSceneClassifier {
    fun classify(
        face: RectN,
        person: RectN?,
        objectCount: Int,
        backgroundRatio: Float,
        symmetry: Float,
        preferredTemplateId: String? = null,
    ): PortraitSceneEvidence {
        val coverage = when {
            person == null -> PortraitCoverage.FACE_ONLY
            face.height / person.height.coerceAtLeast(0.01f) >= 0.22f || person.bottom > 0.96f -> PortraitCoverage.UPPER_BODY
            person.bottom <= 0.96f && person.height >= 0.42f -> PortraitCoverage.FULL_BODY
            else -> PortraitCoverage.UPPER_BODY
        }
        return PortraitSceneEvidence(
            coverage,
            face,
            person,
            objectCount = objectCount,
            backgroundRatio = backgroundRatio,
            sceneSymmetry = symmetry,
            preferredTemplateId = preferredTemplateId,
        )
    }
}

/** V3 always resolves a person to a bracket, never a live skeleton. */
fun PortraitFramingTemplate.toLayoutSlot(): LayoutSlot = LayoutSlot(
    id = "person",
    expectedCategory = GuideObjectCategory.PERSON,
    bounds = personBounds,
    role = SlotRole.PERSON,
    visualKind = SlotVisualKind.PERSON_BRACKET,
    preferredAspectRatio = personBounds.width / personBounds.height.coerceAtLeast(0.01f),
)
