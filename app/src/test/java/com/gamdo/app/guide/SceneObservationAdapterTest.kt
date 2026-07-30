package com.gamdo.app.guide

import com.gamdo.app.detect.DetectionResult
import com.gamdo.app.detect.NormalizedBox
import com.gamdo.app.detect.ObjectObservation
import com.gamdo.app.detect.SegmentationObservation
import com.gamdo.app.detect.SegmentationPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class SceneObservationAdapterTest {

    // ------------------------------------------- 사람 신뢰도 (review_report #17)

    private fun faceOnly(
        leftEyeOpen: Float?,
        rightEyeOpen: Float? = leftEyeOpen,
    ) = DetectionResult(
        faces = listOf(
            com.gamdo.app.detect.FaceObservation(
                box = NormalizedBox(0.35f, 0.15f, 0.65f, 0.45f),
                leftEyeOpenProbability = leftEyeOpen,
                rightEyeOpenProbability = rightEyeOpen,
                headEulerAngleZ = 0f,
            ),
        ),
        pose = null,
    )

    /**
     * A detected face means a person is present. That is what the detector
     * asserted, and it is the only thing it asserted.
     *
     * The old code read `leftEyeOpenProbability` here when pose detection had
     * failed — which is common for upper-body and backlit framing. Eye-open
     * probability measures whether an eyelid is raised; using it as *detection*
     * confidence meant a blink, or a subject wearing sunglasses, or simply the
     * classifier being switched off, reported "no confident subject" for a person
     * standing in plain view.
     */
    @Test
    fun `a face with no pose is still a confident person`() {
        val observation = faceOnly(leftEyeOpen = 0.9f).toSceneObservation()

        assertEquals(SubjectKind.PERSON, observation.subjectKind)
        org.junit.Assert.assertTrue(
            "a detected face must clear the 0.35 subject gate, got ${observation.subjectConfidence}",
            observation.subjectConfidence >= 0.35f,
        )
    }

    /**
     * The defining property. If these three ever diverge again, someone has put
     * eyelid state back into a detection-confidence slot.
     */
    @Test
    fun `person confidence does not depend on whether the eyes are open`() {
        val open = faceOnly(leftEyeOpen = 0.99f).toSceneObservation().subjectConfidence
        val shut = faceOnly(leftEyeOpen = 0.01f).toSceneObservation().subjectConfidence
        // null is what ML Kit returns with the classifier off — the configuration
        // this app now ships. Under the old code this collapsed to exactly 0f.
        val classifierOff = faceOnly(leftEyeOpen = null).toSceneObservation().subjectConfidence

        assertEquals("eyes open vs shut", open, shut, 0.0001f)
        assertEquals("classifier off must not change the verdict", open, classifierOff, 0.0001f)
    }

    /**
     * V3.1 deliberately does not use pose likelihood for person confidence.
     * A valid face/person box is enough for a fixed portrait bracket.
     */
    @Test
    fun `pose likelihood does not change face confidence`() {
        val withPose = DetectionResult(
            faces = faceOnly(leftEyeOpen = null).faces,
            pose = com.gamdo.app.detect.PoseObservation(
                landmarks = listOf(
                    com.gamdo.app.detect.PoseLandmarkPoint(0, 0.4f, 0.2f, 0.95f),
                    com.gamdo.app.detect.PoseLandmarkPoint(1, 0.6f, 0.2f, 0.95f),
                    com.gamdo.app.detect.PoseLandmarkPoint(2, 0.5f, 0.6f, 0.95f),
                ),
                averageInFrameLikelihood = 0.95f,
            ),
        ).toSceneObservation()

        val withoutPose = faceOnly(leftEyeOpen = null).toSceneObservation()
        assertEquals(withoutPose.subjectConfidence, withPose.subjectConfidence, 0.001f)
    }

    @Test
    fun `object detection becomes an object scene subject`() {
        val observation = DetectionResult(
            faces = emptyList(),
            pose = null,
            objects = listOf(
                ObjectObservation(
                    box = NormalizedBox(0.2f, 0.25f, 0.8f, 0.75f),
                    confidence = 0.86f,
                ),
            ),
        ).toSceneObservation()

        assertEquals(SubjectKind.OBJECT, observation.subjectKind)
        assertEquals(0.86f, observation.subjectConfidence, 0.001f)
        assertEquals(0.2f, observation.subjectBox!!.left, 0.001f)
    }

    @Test
    fun `segmentation outline and bounds take precedence over detector box`() {
        val segmentation = SegmentationObservation(
            outline = listOf(
                SegmentationPoint(0.2f, 0.2f),
                SegmentationPoint(0.4f, 0.2f),
                SegmentationPoint(0.3f, 0.6f),
            ),
            bounds = NormalizedBox(0.2f, 0.2f, 0.4f, 0.6f),
            confidence = 0.91f,
            areaRatio = 0.12f,
        )
        val observation = DetectionResult(
            faces = emptyList(),
            pose = null,
            objects = listOf(
                ObjectObservation(
                    box = NormalizedBox(0.1f, 0.1f, 0.9f, 0.9f),
                    confidence = 0.8f,
                ),
            ),
            segmentation = segmentation,
        ).toSceneObservation()

        assertEquals(segmentation.bounds, observation.subjectBox)
        assertEquals(3, observation.subjectOutline.size)
        assertEquals(0.91f, observation.subjectConfidence, 0.001f)
    }
}
