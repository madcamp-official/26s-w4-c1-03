package com.gamdo.app.guide

import com.gamdo.app.detect.NormalizedBox
import com.gamdo.app.detect.DetectionResult
import com.gamdo.app.detect.GuideObjectCategory
import com.gamdo.app.detect.SceneRecognitionPolicy
import kotlin.math.abs

/** What the camera adapter believes the primary subject is. */
enum class SubjectKind { PERSON, OBJECT, UNKNOWN }

/** Direction in which the subject is looking or moving, when available. */
enum class LeadingDirection { NONE, LEFT, RIGHT }

/**
 * Platform-free scene facts supplied by a detector adapter. All coordinates and
 * ratios are normalized to 0..1. The adapter may be ML Kit, LiteRT, or a test fake.
 */
data class SceneObservation(
    val subjectBox: NormalizedBox? = null,
    val subjectKind: SubjectKind = SubjectKind.UNKNOWN,
    val subjectConfidence: Float = 0f,
    val horizonPosition: Float? = null,
    val leadingDirection: LeadingDirection = LeadingDirection.NONE,
    val openSpaceLeft: Float = 0f,
    val openSpaceRight: Float = 0f,
    val openSpaceTop: Float = 0f,
    val openSpaceBottom: Float = 0f,
    val dominantLineConfidence: Float = 0f,
    val subjectOutline: List<LayoutGuidePoint> = emptyList(),
    val subjectLabels: List<String> = emptyList(),
    val hasReliableOutline: Boolean = false,
    val slotDetections: List<SlotDetection> = emptyList(),
    val objectsFresh: Boolean = true,
) {
    fun normalized(): SceneObservation = copy(
        subjectBox = subjectBox?.clamped(),
        subjectConfidence = subjectConfidence.coerceIn(0f, 1f),
        horizonPosition = horizonPosition?.coerceIn(0f, 1f),
        openSpaceLeft = openSpaceLeft.coerceIn(0f, 1f),
        openSpaceRight = openSpaceRight.coerceIn(0f, 1f),
        openSpaceTop = openSpaceTop.coerceIn(0f, 1f),
        openSpaceBottom = openSpaceBottom.coerceIn(0f, 1f),
        dominantLineConfidence = dominantLineConfidence.coerceIn(0f, 1f),
        subjectOutline = subjectOutline.map {
            LayoutGuidePoint(it.x.coerceIn(0f, 1f), it.y.coerceIn(0f, 1f))
        },
        subjectLabels = subjectLabels.filter { it.isNotBlank() },
        hasReliableOutline = hasReliableOutline || subjectOutline.size >= 3,
        slotDetections = slotDetections.map { it.normalized() },
    )
}

enum class ProposalReason {
    LEADING_SPACE,
    OPEN_SPACE_BALANCE,
    HORIZON_BALANCE,
    STYLE_DEFAULT,
    STATIC_FALLBACK,
}

/** Internal result consumed by the camera guide; confidence/reason are not UI copy. */
data class CompositionProposal(
    val target: StyleTarget,
    val subjectBox: NormalizedBox?,
    val confidence: Float,
    val reason: ProposalReason,
    val fallback: Boolean,
    val stabilized: Boolean = false,
    val candidates: List<CompositionCandidate> = emptyList(),
)

data class CompositionCandidate(
    val target: StyleTarget,
    val score: Float,
    val reason: ProposalReason,
)

/**
 * Confidence assigned to a subject the detector found but did not score.
 *
 * Neither ML Kit's face detector nor its object detector always returns a
 * confidence value; when they do not, the honest reading of a detection is "this
 * is here" with no strength attached. This constant is that reading, shared by
 * both branches so they cannot drift apart.
 *
 * It sits above the 0.35 subject gate (a found subject should not be treated as
 * absent) and above the 0.65 `confidentThreshold`. Clearing the latter is safe:
 * `SceneLayoutGuideEngine` additionally requires `hasReliableOutline`, which a
 * face-only detection does not have, so the outline still will not be projected.
 */
private const val DETECTED_SUBJECT_FLOOR = 0.7f

/** Minimal bridge from the detector aggregate to the proposal contract. */
fun DetectionResult.toSceneObservation(): SceneObservation {
    val poseBox = pose?.landmarks
        ?.filter { it.inFrameLikelihood >= 0.3f }
        ?.let { points ->
            if (points.isEmpty()) null else NormalizedBox(
                left = points.minOf { it.x },
                top = points.minOf { it.y },
                right = points.maxOf { it.x },
                bottom = points.maxOf { it.y },
            )
        }
    val personBox = objects.firstOrNull { it.category == GuideObjectCategory.PERSON }?.box
        ?: poseBox
        ?: faces.maxByOrNull { it.box.width * it.box.height }?.box
    val objectCandidate = objects
        .filter { it.box.width > 0f && it.box.height > 0f }
        .maxByOrNull { it.box.width * it.box.height * (it.detectionConfidence ?: it.confidence.takeIf { confidence -> confidence > 0f } ?: 0.7f) }
    val segmented = segmentation
    val subjectBox = segmented?.bounds ?: personBox ?: objectCandidate?.box
    val kind = when {
        personBox != null -> SubjectKind.PERSON
        objectCandidate != null -> SubjectKind.OBJECT
        else -> SubjectKind.UNKNOWN
    }
    val detectorConfidence = when {
        // Pose likelihood is a real measurement of person-presence, so it wins.
        // Without it, ML Kit's face detector exposes **no confidence at all** —
        // DETECTED_SUBJECT_FLOOR stands in for a number that does not exist,
        // exactly as the object branch below already does.
        //
        // This used to fall back to `leftEyeOpenProbability`. That is eyelid
        // state, not detection confidence: a blink, sunglasses, or simply the
        // classifier being off reported "no confident subject" for a person
        // standing in plain view. Pose detection fails routinely on upper-body
        // and backlit framing, so that fallback was the common path, not the rare
        // one. `SceneObservationAdapterTest` pins the property that eye state
        // cannot move this number.
        personBox != null -> pose?.averageInFrameLikelihood ?: DETECTED_SUBJECT_FLOOR
        objectCandidate != null -> objectCandidate.detectionConfidence
            ?: objectCandidate.confidence.takeIf { it > 0f }
            ?: DETECTED_SUBJECT_FLOOR
        else -> 0f
    }
    val confidence = maxOf(detectorConfidence, segmented?.confidence ?: 0f)
    return SceneObservation(
        subjectBox = subjectBox,
        subjectKind = kind,
        subjectConfidence = confidence,
        subjectOutline = segmented?.outline
            ?.map { LayoutGuidePoint(it.x, it.y) }
            ?.takeIf { it.size >= 3 }
            .orEmpty()
            .ifEmpty {
                pose?.landmarks
                    ?.filter { it.inFrameLikelihood >= 0.3f }
                    ?.map { LayoutGuidePoint(it.x, it.y) }
                    .orEmpty()
            },
        subjectLabels = objectCandidate?.labels.orEmpty(),
        hasReliableOutline = when {
            segmented?.outline?.size ?: 0 >= 3 -> true
            personBox != null -> pose != null || objects.any { it.category == GuideObjectCategory.PERSON }
            else -> objectCandidate?.mask?.outline?.size?.let { it >= 3 } == true
        },
        slotDetections = buildList {
            if (personBox != null) {
                add(
                    SlotDetection(
                        id = "person",
                        category = com.gamdo.app.detect.GuideObjectCategory.PERSON,
                        bounds = personBox,
                        confidence = detectorConfidence,
                        isReliable = true,
                        role = SlotRole.PERSON,
                        visualKind = SlotVisualKind.PERSON_SILHOUETTE,
                    ),
                )
            }
            objects.forEachIndexed { index, detectedObject ->
                add(
                    SlotDetection(
                        id = detectedObject.trackingId?.toString() ?: "object-$index",
                        category = detectedObject.category,
                        bounds = detectedObject.box,
                        confidence = detectedObject.detectionConfidence
                            ?: detectedObject.confidence.takeIf { it > 0f }
                            ?: 0.7f,
                        isReliable = SceneRecognitionPolicy.isValidBox(detectedObject.box),
                        role = SlotRole.OBJECT,
                        visualKind = when (detectedObject.category) {
                            com.gamdo.app.detect.GuideObjectCategory.DRINKWARE -> SlotVisualKind.CUP
                            com.gamdo.app.detect.GuideObjectCategory.FOOD_TABLEWARE -> SlotVisualKind.PLATE
                            else -> SlotVisualKind.GENERIC_OBJECT
                        },
                        semanticConfidence = detectedObject.classificationConfidence,
                        semanticConfirmed = detectedObject.semanticConfirmed,
                        outline = detectedObject.mask?.outline
                            ?.map { LayoutGuidePoint(it.x, it.y) }
                            .orEmpty(),
                        stableObjectKey = detectedObject.stableObjectKey,
                    ),
                )
            }
        },
        objectsFresh = objectsFresh,
    )
}

/**
 * Turns scene facts into a useful target instead of returning one fixed rectangle.
 * The engine deliberately does not render text, arrows, scores, or auto-shutter UI.
 */
class SceneProposalEngine(
    private val minSubjectConfidence: Float = 0.35f,
    private val movementThreshold: Float = 0.08f,
) {
    private var previous: CompositionProposal? = null

    fun propose(
        observation: SceneObservation,
        styleTarget: StyleTarget,
    ): CompositionProposal {
        val scene = observation.normalized()
        val subjectUsable = scene.subjectBox != null && scene.subjectConfidence >= minSubjectConfidence
        if (!subjectUsable) {
            val fallback = CompositionProposal(
                target = styleTarget,
                subjectBox = scene.subjectBox,
                confidence = scene.subjectConfidence,
                reason = ProposalReason.STATIC_FALLBACK,
                fallback = true,
                candidates = listOf(CompositionCandidate(styleTarget, 0f, ProposalReason.STATIC_FALLBACK)),
            )
            previous = fallback
            return fallback
        }

        val candidates = buildCandidates(scene, styleTarget)
        val selected = candidates.maxBy { it.score }
        val candidate = selected.target
        val reason = selected.reason
        val confidence = confidence(scene, reason)
        val prior = previous
        if (prior != null && !prior.fallback && targetDistance(prior.target, candidate) <= movementThreshold) {
            return prior.copy(
                subjectBox = scene.subjectBox,
                confidence = confidence,
                stabilized = true,
                candidates = candidates,
            ).also { previous = it }
        }
        return CompositionProposal(
            target = candidate,
            subjectBox = scene.subjectBox,
            confidence = confidence,
            reason = reason,
            fallback = false,
            candidates = candidates,
        ).also { previous = it }
    }

    fun reset() {
        previous = null
    }

    private fun preferredAnchor(scene: SceneObservation, style: StyleTarget): Float {
        return when {
            scene.leadingDirection == LeadingDirection.RIGHT -> 1f / 3f
            scene.leadingDirection == LeadingDirection.LEFT -> 2f / 3f
            scene.openSpaceRight >= scene.openSpaceLeft + 0.12f -> 1f / 3f
            scene.openSpaceLeft >= scene.openSpaceRight + 0.12f -> 2f / 3f
            else -> style.subjectAnchorX
        }.coerceIn(0.15f, 0.85f)
    }

    private fun buildCandidates(
        scene: SceneObservation,
        style: StyleTarget,
    ): List<CompositionCandidate> {
        val anchors = listOf(1f / 3f, style.subjectAnchorX, 2f / 3f).distinct()
        return anchors.map { anchor ->
            val horizon = if (scene.dominantLineConfidence >= 0.35f) {
                preferredHorizon(scene, style)
            } else {
                style.horizonPosition
            }
            val target = style.copy(subjectAnchorX = anchor, horizonPosition = horizon)
            CompositionCandidate(
                target = target,
                score = candidateScore(scene, style, anchor, horizon),
                reason = reasonFor(scene, anchor, style),
            )
        }.sortedByDescending { it.score }
    }

    private fun candidateScore(
        scene: SceneObservation,
        style: StyleTarget,
        anchor: Float,
        horizon: Float,
    ): Float {
        val styleAffinity = (1f - abs(anchor - style.subjectAnchorX)).coerceIn(0f, 1f)
        val openSpaceAffinity = when {
            scene.leadingDirection == LeadingDirection.RIGHT -> (1f - anchor).coerceIn(0f, 1f)
            scene.leadingDirection == LeadingDirection.LEFT -> anchor
            scene.openSpaceRight > scene.openSpaceLeft -> (1f - anchor).coerceIn(0f, 1f)
            scene.openSpaceLeft > scene.openSpaceRight -> anchor
            else -> 0.5f
        }
        val horizonAffinity = scene.horizonPosition?.let {
            (1f - abs(it - horizon) / 0.5f).coerceIn(0f, 1f)
        } ?: 0.5f
        return (styleAffinity * 0.35f + openSpaceAffinity * 0.45f + horizonAffinity * 0.20f)
            .coerceIn(0f, 1f)
    }

    private fun preferredHorizon(scene: SceneObservation, style: StyleTarget): Float {
        val detected = scene.horizonPosition ?: return style.horizonPosition
        if (scene.dominantLineConfidence < 0.35f) return style.horizonPosition
        return when {
            detected < 0.42f -> 1f / 3f
            detected > 0.58f -> 2f / 3f
            else -> style.horizonPosition
        }
    }

    private fun reasonFor(scene: SceneObservation, anchor: Float, style: StyleTarget): ProposalReason = when {
        scene.leadingDirection != LeadingDirection.NONE -> ProposalReason.LEADING_SPACE
        abs(scene.openSpaceRight - scene.openSpaceLeft) >= 0.12f -> ProposalReason.OPEN_SPACE_BALANCE
        scene.horizonPosition != null && scene.dominantLineConfidence >= 0.35f -> ProposalReason.HORIZON_BALANCE
        abs(anchor - style.subjectAnchorX) < 0.01f -> ProposalReason.STYLE_DEFAULT
        else -> ProposalReason.OPEN_SPACE_BALANCE
    }

    private fun confidence(scene: SceneObservation, reason: ProposalReason): Float {
        val structure = when (reason) {
            ProposalReason.LEADING_SPACE -> 0.85f
            ProposalReason.OPEN_SPACE_BALANCE -> 0.75f
            ProposalReason.HORIZON_BALANCE -> 0.7f
            ProposalReason.STYLE_DEFAULT -> 0.55f
            ProposalReason.STATIC_FALLBACK -> 0.2f
        }
        return (scene.subjectConfidence * 0.6f + structure * 0.25f +
            scene.dominantLineConfidence * 0.15f).coerceIn(0f, 1f)
    }

    private fun targetDistance(a: StyleTarget, b: StyleTarget): Float =
        abs(a.subjectAnchorX - b.subjectAnchorX) + abs(a.horizonPosition - b.horizonPosition)
}

private fun NormalizedBox.clamped(): NormalizedBox = NormalizedBox(
    left = left.coerceIn(0f, 1f),
    top = top.coerceIn(0f, 1f),
    right = right.coerceIn(left.coerceIn(0f, 1f), 1f),
    bottom = bottom.coerceIn(top.coerceIn(0f, 1f), 1f),
)
