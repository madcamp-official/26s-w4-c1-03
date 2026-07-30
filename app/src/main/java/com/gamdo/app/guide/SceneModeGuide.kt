package com.gamdo.app.guide

import com.gamdo.app.detect.GuideObjectCategory
import kotlin.math.sqrt

/** The user-facing capture intention. Object names are deliberately absent. */
enum class CaptureSceneMode {
    AUTO,
    PORTRAIT,
    ENVIRONMENTAL_PORTRAIT,
    CAFE_FOOD,
    TRAVEL_LANDSCAPE,
    STILL_LIFE,
}

enum class SceneModeSource { AUTO_CLASSIFIER, USER }

data class SceneModeDecision(
    val suggested: CaptureSceneMode,
    val confidence: Float,
    val source: SceneModeSource,
) {
    init {
        require(confidence in 0f..1f)
        require(suggested != CaptureSceneMode.AUTO)
    }
}

data class SceneGuideRequest(
    val mode: CaptureSceneMode,
    val scope: SceneSearchScope,
    val style: StyleTarget,
)

/** A product-renderable mark. Raw detector boxes, labels and confidence never cross this seam. */
sealed interface GuideMark {
    data class SubjectDot(
        val id: Long,
        val center: PointN,
        val radius: Float,
        val weight: SubjectWeight,
    ) : GuideMark {
        init {
            require(radius in 0f..0.5f)
        }
    }

    data class PersonSilhouette(
        val bounds: RectN,
        val framing: PortraitSilhouetteKind,
        val mirrored: Boolean,
    ) : GuideMark

    data class HorizonLine(val y: Float) : GuideMark {
        init { require(y in 0f..1f) }
    }
}

enum class SubjectWeight { HERO, EQUAL, SUPPORTING }

data class SceneGuideMarks(
    val mode: CaptureSceneMode,
    val templateId: String,
    val marks: List<GuideMark>,
    val fixed: Boolean = true,
) {
    init {
        require(templateId.isNotBlank())
        require(marks.isNotEmpty())
    }
}

/** Inputs intentionally limited to cheap evidence already available on device. */
data class SceneModeEvidence(
    val faces: Int,
    val people: Int,
    val objects: Int,
    val backgroundRatio: Float? = null,
    val horizonY: Float? = null,
    val tiltDeg: Float? = null,
    val sceneSymmetry: Float? = null,
) {
    init {
        require(faces >= 0 && people >= 0 && objects >= 0)
        require(backgroundRatio == null || backgroundRatio in 0f..1f)
        require(horizonY == null || horizonY in 0f..1f)
    }
}

/**
 * Conservative baseline used until the trained scene classifier is bundled.
 * A low-confidence result is intentionally represented as null, so AUTO never
 * invents a guide for an ambiguous frame.
 */
interface SceneModeClassifier {
    fun classify(evidence: SceneModeEvidence): SceneModeDecision?
}

class HeuristicSceneModeClassifier(
    private val minimumConfidence: Float = 0.65f,
) : SceneModeClassifier {
    init { require(minimumConfidence in 0f..1f) }

    override fun classify(evidence: SceneModeEvidence): SceneModeDecision? {
        val decision = when {
            evidence.people > 0 || evidence.faces > 0 -> {
                val environmental = (evidence.backgroundRatio ?: 0f) >= 0.45f
                SceneModeDecision(
                    if (environmental) CaptureSceneMode.ENVIRONMENTAL_PORTRAIT else CaptureSceneMode.PORTRAIT,
                    if (environmental) 0.78f else 0.76f,
                    SceneModeSource.AUTO_CLASSIFIER,
                )
            }
            evidence.horizonY != null && (evidence.objects == 0 || evidence.objects <= 1) ->
                SceneModeDecision(CaptureSceneMode.TRAVEL_LANDSCAPE, 0.68f, SceneModeSource.AUTO_CLASSIFIER)
            evidence.objects > 0 ->
                SceneModeDecision(CaptureSceneMode.STILL_LIFE, 0.66f, SceneModeSource.AUTO_CLASSIFIER)
            else -> null
        }
        return decision?.takeIf { it.confidence >= minimumConfidence }
    }
}

/** Pure Kotlin mode-specific technique selection. Coordinates are fixed targets. */
class SceneTechniqueSelector {
    fun select(
        mode: CaptureSceneMode,
        detections: List<SlotDetection>,
        evidence: SceneModeEvidence = SceneModeEvidence(0, 0, 0),
        style: StyleTarget = StyleTarget(),
    ): SceneGuideMarks? {
        val objects = detections.filter { it.role == SlotRole.OBJECT && it.isReliable }.take(4)
        val person = detections.firstOrNull { it.role == SlotRole.PERSON && it.isReliable }
        return when (mode) {
            CaptureSceneMode.AUTO -> null
            CaptureSceneMode.PORTRAIT -> personMarks(person, PortraitSilhouetteKind.FULL_CENTER, "portrait_center_v2", style)
            CaptureSceneMode.ENVIRONMENTAL_PORTRAIT -> personMarks(person, PortraitSilhouetteKind.ENVIRONMENTAL, "portrait_environment_v2", style, evidence)
            CaptureSceneMode.TRAVEL_LANDSCAPE -> travelMarks(person, evidence)
            CaptureSceneMode.CAFE_FOOD -> objectMarks(objects, "cafe")
            CaptureSceneMode.STILL_LIFE -> objectMarks(objects, "still_life")
        }
    }

    private fun personMarks(
        person: SlotDetection?,
        kind: PortraitSilhouetteKind,
        id: String,
        style: StyleTarget,
        evidence: SceneModeEvidence = SceneModeEvidence(0, 1, 0),
    ): SceneGuideMarks? {
        if (person == null) return null
        val face = RectN(person.bounds.left, person.bounds.top, person.bounds.right, (person.bounds.top + person.bounds.height * 0.25f).coerceAtMost(1f))
        val portrait = PortraitSceneClassifier.classify(
            face = face,
            person = RectN(person.bounds.left, person.bounds.top, person.bounds.right, person.bounds.bottom),
            objectCount = 0,
            backgroundRatio = evidence.backgroundRatio ?: style.backgroundRatioRange?.let { (it.start + it.endInclusive) / 2f } ?: 0.5f,
            symmetry = evidence.sceneSymmetry ?: 0f,
            preferredTemplateId = style.preferredPortraitTemplateId,
        )
        val selected = when {
            kind == PortraitSilhouetteKind.ENVIRONMENTAL -> PortraitFramingCatalog.environmental
            portrait.coverage == PortraitCoverage.FACE_ONLY -> PortraitFramingCatalog.upper
            portrait.coverage == PortraitCoverage.FULL_BODY && kind == PortraitSilhouetteKind.FULL_CENTER -> PortraitFramingCatalog.fullCenter
            portrait.coverage == PortraitCoverage.FULL_BODY -> PortraitFramingCatalog.fullThirds
            else -> PortraitFramingCatalog.upper
        }
        return SceneGuideMarks(
            mode = if (kind == PortraitSilhouetteKind.ENVIRONMENTAL) CaptureSceneMode.ENVIRONMENTAL_PORTRAIT else CaptureSceneMode.PORTRAIT,
            templateId = id,
            marks = listOf(GuideMark.PersonSilhouette(selected.personBounds, selected.silhouetteKind, mirrored = false)),
        )
    }

    private fun travelMarks(person: SlotDetection?, evidence: SceneModeEvidence): SceneGuideMarks? {
        val marks = buildList {
            evidence.horizonY?.let { add(GuideMark.HorizonLine(it)) }
            person?.let {
                add(GuideMark.PersonSilhouette(RectN(0.16f, 0.10f, 0.50f, 0.90f), PortraitSilhouetteKind.ENVIRONMENTAL, false))
            }
        }
        return marks.takeIf { it.isNotEmpty() }?.let { SceneGuideMarks(CaptureSceneMode.TRAVEL_LANDSCAPE, "travel_rule_of_thirds_v2", it) }
    }

    private fun objectMarks(objects: List<SlotDetection>, prefix: String): SceneGuideMarks? {
        if (objects.isEmpty()) return null
        val centers = when (objects.size) {
            1 -> listOf(PointN(0.50f, 0.55f))
            2 -> listOf(PointN(0.33f, 0.56f), PointN(0.67f, 0.56f))
            3 -> listOf(PointN(0.50f, 0.35f), PointN(0.31f, 0.67f), PointN(0.69f, 0.67f))
            else -> listOf(PointN(0.50f, 0.28f), PointN(0.28f, 0.52f), PointN(0.72f, 0.52f), PointN(0.50f, 0.76f))
        }
        val marks = objects.mapIndexed { index, detection ->
            val area = (detection.bounds.width * detection.bounds.height).coerceIn(0.04f, 0.14f)
            val weight = when {
                objects.size == 1 || index == 0 && objects.size >= 3 -> SubjectWeight.HERO
                objects.size == 2 -> SubjectWeight.EQUAL
                else -> SubjectWeight.SUPPORTING
            }
            GuideMark.SubjectDot(
                id = detection.stableObjectKey.hashCode().toLong(),
                center = centers[index],
                radius = sqrt(area).coerceIn(0.08f, 0.20f),
                weight = weight,
            )
        }
        return SceneGuideMarks(
            mode = if (prefix == "cafe") CaptureSceneMode.CAFE_FOOD else CaptureSceneMode.STILL_LIFE,
            templateId = "${prefix}_${objects.size}_technique_v2",
            marks = marks,
        )
    }
}

/** Compatibility adapter for P1 while the overlay migrates from LayoutSlot to GuideMark. */
object GuideMarkAdapter {
    fun fromTemplate(template: LayoutTemplate): List<GuideMark> = buildList {
        template.horizonY?.let { add(GuideMark.HorizonLine(it)) }
        template.slots.forEach { slot ->
            if (slot.role == SlotRole.PERSON) {
                add(GuideMark.PersonSilhouette(slot.bounds, PortraitSilhouetteKind.ENVIRONMENTAL, false))
            } else {
                val area = (slot.bounds.width * slot.bounds.height).coerceIn(0.04f, 0.14f)
                add(GuideMark.SubjectDot(slot.id.hashCode().toLong(), PointN((slot.bounds.left + slot.bounds.right) / 2f, (slot.bounds.top + slot.bounds.bottom) / 2f), sqrt(area).coerceIn(0.08f, 0.20f), SubjectWeight.EQUAL))
            }
        }
    }
}
