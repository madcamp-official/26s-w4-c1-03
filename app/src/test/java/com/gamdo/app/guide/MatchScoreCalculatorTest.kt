package com.gamdo.app.guide

import com.gamdo.app.detect.DetectionResult
import com.gamdo.app.detect.FrameFeatureCalculator
import com.gamdo.app.detect.FrameFeatureInput
import com.gamdo.app.detect.NormalizedBox
import com.gamdo.app.detect.PoseObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchScoreCalculatorTest {
    private val calculator = MatchScoreCalculator()
    private val targets = listOf(
        StyleTarget(targetAspectRatio = 0.8f, subjectScaleRange = 0.35f..0.55f, subjectAnchorX = 0.5f),
        StyleTarget(targetAspectRatio = 0.8f, subjectScaleRange = 0.3f..0.5f, subjectAnchorX = 1f / 3f),
        StyleTarget(targetAspectRatio = 1f, subjectScaleRange = 0.45f..0.7f, subjectAnchorX = 0.5f),
        StyleTarget(targetAspectRatio = 0.8f, subjectScaleRange = 0.32f..0.52f, subjectAnchorX = 2f / 3f),
        StyleTarget(targetAspectRatio = 0.8f, subjectScaleRange = 0.4f..0.62f, subjectAnchorX = 0.5f),
        StyleTarget(targetAspectRatio = 0.8f, subjectScaleRange = 0.3f..0.5f, subjectAnchorX = 1f / 3f),
    )

    private val scenes = listOf(
        scene(box = NormalizedBox(0.3f, 0.05f, 0.7f, 0.45f), headroom = 0.05f),
        scene(box = NormalizedBox(0.1f, 0.12f, 0.4f, 0.72f), headroom = 0.12f),
        scene(box = NormalizedBox(0.5f, 0.02f, 0.95f, 0.72f), headroom = 0.02f),
        scene(box = null, headroom = 0f, lowLight = true),
    )

    @Test
    fun `six presets and four scenes produce bounded score snapshots`() {
        val snapshots = targets.flatMap { target ->
            scenes.map { scene -> calculator.calculate(scene, target) }
        }

        assertEquals(24, snapshots.size)
        assertTrue(snapshots.all { it in 0f..1f })
        assertTrue(snapshots.distinct().size >= 6)
    }

    @Test
    fun `matching central scene scores higher than missing person`() {
        val target = targets.first()
        val matching = calculator.calculate(scenes.first(), target)
        val missing = calculator.calculate(scenes.last(), target)

        assertTrue(matching > missing)
        assertNotEquals(0f, matching)
    }

    @Test
    fun `preset composition converts to style target`() {
        val preset = com.gamdo.app.data.preset.StylePreset(
            id = "candid_feed",
            name = "Candid Feed",
            displayName = "자연스러운 피드",
            composition = com.gamdo.app.data.preset.Composition(
                targetAspectRatio = "4:5",
                subjectScaleRange = listOf(0.3, 0.5),
                subjectPosition = "third_left",
                headroomRange = listOf(0.06, 0.14),
                horizonPosition = 0.55,
                cameraPitchRange = listOf(-6.0, 6.0),
                posePattern = "candid_motion",
                backgroundRatio = listOf(0.45, 0.65),
            ),
            color = com.gamdo.app.data.preset.ColorParams(5500.0, 0.15, 0.05, 0.0, 0.08, 0.05, 0.0, 0.12),
        )

        val target = preset.toStyleTarget()

        assertEquals(0.8f, target.targetAspectRatio, 0.0001f)
        assertEquals(1f / 3f, target.subjectAnchorX, 0.0001f)
        assertEquals(0.55f, target.horizonPosition, 0.0001f)
    }

    private fun scene(box: NormalizedBox?, headroom: Float, lowLight: Boolean = false) =
        FrameFeatureCalculator().calculate(
            FrameFeatureInput(
                detection = DetectionResult(emptyList(), box?.let { PoseObservation(emptyList(), 0.9f) }),
                personCandidates = listOfNotNull(box),
                brightness = com.gamdo.app.detect.BrightnessSample(
                    frameMean = if (lowLight) 0.1f else 0.5f,
                ),
            ),
        ).copy(headroom = headroom)
}
