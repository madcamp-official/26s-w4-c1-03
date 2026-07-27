package com.gamdo.app.guide

import com.gamdo.app.detect.DetectionResult
import com.gamdo.app.detect.NormalizedBox
import com.gamdo.app.detect.ObjectObservation
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
}
