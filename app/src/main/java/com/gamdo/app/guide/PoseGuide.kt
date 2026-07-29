package com.gamdo.app.guide

import com.gamdo.app.detect.PoseObservation

enum class PoseCoverage { FULL_BODY, UPPER_BODY, SEATED }

enum class PoseJoint {
    HEAD, LEFT_SHOULDER, RIGHT_SHOULDER, LEFT_ELBOW, RIGHT_ELBOW,
    LEFT_WRIST, RIGHT_WRIST, LEFT_HIP, RIGHT_HIP, LEFT_KNEE, RIGHT_KNEE,
    LEFT_ANKLE, RIGHT_ANKLE,
}

data class PoseTargetNode(val joint: PoseJoint, val point: PointN)
data class PoseTargetEdge(val from: PoseJoint, val to: PoseJoint)

data class PoseGuideTemplate(
    val id: String,
    val nodes: List<PoseTargetNode>,
    val edges: List<PoseTargetEdge>,
    val requiredCoverage: PoseCoverage,
)

object PoseGuideCatalog {
    const val FULL_CENTER = "pose_full_center_v1"
    const val FULL_OFFSET = "pose_full_offset_v1"
    const val UPPER_BODY = "pose_upper_body_v1"
    const val SEATED = "pose_seated_v1"

    private val standardEdges = listOf(
        PoseTargetEdge(PoseJoint.HEAD, PoseJoint.LEFT_SHOULDER),
        PoseTargetEdge(PoseJoint.HEAD, PoseJoint.RIGHT_SHOULDER),
        PoseTargetEdge(PoseJoint.LEFT_SHOULDER, PoseJoint.RIGHT_SHOULDER),
        PoseTargetEdge(PoseJoint.LEFT_SHOULDER, PoseJoint.LEFT_ELBOW),
        PoseTargetEdge(PoseJoint.LEFT_ELBOW, PoseJoint.LEFT_WRIST),
        PoseTargetEdge(PoseJoint.RIGHT_SHOULDER, PoseJoint.RIGHT_ELBOW),
        PoseTargetEdge(PoseJoint.RIGHT_ELBOW, PoseJoint.RIGHT_WRIST),
        PoseTargetEdge(PoseJoint.LEFT_SHOULDER, PoseJoint.LEFT_HIP),
        PoseTargetEdge(PoseJoint.RIGHT_SHOULDER, PoseJoint.RIGHT_HIP),
        PoseTargetEdge(PoseJoint.LEFT_HIP, PoseJoint.RIGHT_HIP),
        PoseTargetEdge(PoseJoint.LEFT_HIP, PoseJoint.LEFT_KNEE),
        PoseTargetEdge(PoseJoint.LEFT_KNEE, PoseJoint.LEFT_ANKLE),
        PoseTargetEdge(PoseJoint.RIGHT_HIP, PoseJoint.RIGHT_KNEE),
        PoseTargetEdge(PoseJoint.RIGHT_KNEE, PoseJoint.RIGHT_ANKLE),
    )

    fun resolve(id: String?): PoseGuideTemplate? = when (id) {
        FULL_CENTER -> template(id, PoseCoverage.FULL_BODY, listOf(
            0.50f to 0.18f, 0.43f to 0.28f, 0.57f to 0.28f, 0.38f to 0.43f, 0.62f to 0.43f,
            0.34f to 0.56f, 0.66f to 0.56f, 0.45f to 0.53f, 0.55f to 0.53f,
            0.43f to 0.72f, 0.57f to 0.72f, 0.42f to 0.91f, 0.58f to 0.91f,
        ))
        FULL_OFFSET -> template(id, PoseCoverage.FULL_BODY, listOf(
            0.42f to 0.18f, 0.36f to 0.29f, 0.49f to 0.27f, 0.30f to 0.43f, 0.55f to 0.40f,
            0.27f to 0.56f, 0.61f to 0.48f, 0.39f to 0.53f, 0.51f to 0.51f,
            0.35f to 0.72f, 0.58f to 0.69f, 0.31f to 0.91f, 0.64f to 0.88f,
        ))
        UPPER_BODY -> template(id, PoseCoverage.UPPER_BODY, listOf(
            0.50f to 0.24f, 0.38f to 0.39f, 0.62f to 0.39f, 0.31f to 0.56f, 0.69f to 0.56f,
            0.35f to 0.72f, 0.65f to 0.72f, 0.43f to 0.72f, 0.57f to 0.72f,
            0.43f to 0.90f, 0.57f to 0.90f, 0.43f to 0.96f, 0.57f to 0.96f,
        ))
        SEATED -> template(id, PoseCoverage.SEATED, listOf(
            0.50f to 0.20f, 0.41f to 0.32f, 0.59f to 0.32f, 0.35f to 0.48f, 0.65f to 0.48f,
            0.42f to 0.58f, 0.58f to 0.58f, 0.44f to 0.57f, 0.56f to 0.57f,
            0.35f to 0.70f, 0.65f to 0.70f, 0.30f to 0.87f, 0.70f to 0.87f,
        ))
        else -> null
    }

    private fun template(id: String, coverage: PoseCoverage, coordinates: List<Pair<Float, Float>>): PoseGuideTemplate {
        val joints = PoseJoint.entries
        return PoseGuideTemplate(
            id = id,
            nodes = coordinates.mapIndexed { index, (x, y) -> PoseTargetNode(joints[index], PointN(x, y)) },
            edges = if (coverage == PoseCoverage.UPPER_BODY) standardEdges.filter {
                it.from !in lowerBody || it.to !in lowerBody
            }.filter { it.from !in lowerBody && it.to !in lowerBody } else standardEdges,
            requiredCoverage = coverage,
        )
    }

    private val lowerBody = setOf(PoseJoint.LEFT_KNEE, PoseJoint.RIGHT_KNEE, PoseJoint.LEFT_ANKLE, PoseJoint.RIGHT_ANKLE)
}

object PoseGuideSelector {
    private const val MIN_LIKELIHOOD = 0.45f
    private val shouldersAndHips = setOf(11, 12, 23, 24)
    private val lowerBody = setOf(25, 26, 27, 28)

    fun select(pose: PoseObservation?): PoseGuideTemplate? {
        pose ?: return null
        val confident = pose.landmarks.filter { it.inFrameLikelihood >= MIN_LIKELIHOOD }.associateBy { it.type }
        if (!shouldersAndHips.all(confident::containsKey)) return null
        if (!lowerBody.all(confident::containsKey)) return PoseGuideCatalog.resolve(PoseGuideCatalog.UPPER_BODY)
        val leftHip = confident.getValue(23)
        val rightHip = confident.getValue(24)
        val leftKnee = confident.getValue(25)
        val rightKnee = confident.getValue(26)
        val hipY = (leftHip.y + rightHip.y) / 2f
        val kneeY = (leftKnee.y + rightKnee.y) / 2f
        val kneeSpan = kotlin.math.abs(leftKnee.x - rightKnee.x)
        return if (kneeY - hipY < 0.16f || kneeSpan > 0.28f) {
            PoseGuideCatalog.resolve(PoseGuideCatalog.SEATED)
        } else {
            PoseGuideCatalog.resolve(PoseGuideCatalog.FULL_CENTER)
        }
    }
}
