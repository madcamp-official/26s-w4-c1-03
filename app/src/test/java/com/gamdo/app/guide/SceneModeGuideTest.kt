package com.gamdo.app.guide

import com.gamdo.app.detect.GuideObjectCategory
import com.gamdo.app.detect.NormalizedBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneModeGuideTest {
    private val selector = SceneTechniqueSelector()

    @Test
    fun `ambiguous auto evidence does not invent a mode`() {
        val decision = HeuristicSceneModeClassifier().classify(SceneModeEvidence(0, 0, 0))
        assertNull(decision)
    }

    @Test
    fun `people with background propose environmental portrait`() {
        val decision = HeuristicSceneModeClassifier().classify(
            SceneModeEvidence(faces = 1, people = 1, objects = 0, backgroundRatio = 0.62f),
        )
        assertEquals(CaptureSceneMode.ENVIRONMENTAL_PORTRAIT, decision?.suggested)
        assertEquals(SceneModeSource.AUTO_CLASSIFIER, decision?.source)
    }

    @Test
    fun `object subjects become one fixed dot per selected subject`() {
        val detections = (0 until 3).map { index ->
            SlotDetection(
                id = "o$index",
                category = GuideObjectCategory.UNKNOWN,
                bounds = NormalizedBox(0.1f + index * 0.1f, 0.4f, 0.18f + index * 0.1f, 0.55f),
                confidence = 0.8f,
                isReliable = true,
                stableObjectKey = "track-$index",
            )
        }
        val result = selector.select(CaptureSceneMode.STILL_LIFE, detections)
        assertEquals(3, result?.marks?.filterIsInstance<GuideMark.SubjectDot>()?.size)
        assertTrue(result!!.marks.none { it is GuideMark.PersonSilhouette })
        assertTrue(result.marks.filterIsInstance<GuideMark.SubjectDot>().all { it.radius in 0.08f..0.20f })
    }

    @Test
    fun `portrait output is a silhouette and not a detector box`() {
        val person = SlotDetection(
            id = "person",
            category = GuideObjectCategory.PERSON,
            bounds = NormalizedBox(0.30f, 0.08f, 0.70f, 0.94f),
            confidence = 0.9f,
            isReliable = true,
        )
        val result = selector.select(
            CaptureSceneMode.PORTRAIT,
            listOf(person),
            SceneModeEvidence(faces = 1, people = 1, objects = 0),
        )
        assertEquals(1, result?.marks?.size)
        assertTrue(result?.marks?.single() is GuideMark.PersonSilhouette)
    }

    @Test
    fun `travel can expose horizon without running object detection`() {
        val result = selector.select(
            CaptureSceneMode.TRAVEL_LANDSCAPE,
            emptyList(),
            SceneModeEvidence(faces = 0, people = 0, objects = 0, horizonY = 0.34f),
        )
        assertEquals(listOf(GuideMark.HorizonLine(0.34f)), result?.marks)
    }

    @Test
    fun `legacy layout adapts to dots and silhouettes`() {
        val template = LayoutTemplate(
            id = "compat",
            slots = listOf(
                LayoutSlot("person", GuideObjectCategory.PERSON, RectN(0.2f, 0.1f, 0.6f, 0.9f)),
                LayoutSlot("object", GuideObjectCategory.UNKNOWN, RectN(0.6f, 0.4f, 0.8f, 0.6f)),
            ),
            horizonY = 0.5f,
        )
        val marks = GuideMarkAdapter.fromTemplate(template)
        assertTrue(marks.any { it is GuideMark.PersonSilhouette })
        assertTrue(marks.any { it is GuideMark.SubjectDot })
        assertTrue(marks.any { it is GuideMark.HorizonLine })
    }
}
