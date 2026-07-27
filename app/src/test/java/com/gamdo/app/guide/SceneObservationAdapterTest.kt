package com.gamdo.app.guide

import com.gamdo.app.detect.DetectionResult
import com.gamdo.app.detect.NormalizedBox
import com.gamdo.app.detect.ObjectObservation
import com.gamdo.app.detect.SegmentationObservation
import com.gamdo.app.detect.SegmentationPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class SceneObservationAdapterTest {
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
