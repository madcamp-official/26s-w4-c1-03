package com.gamdo.app.detect

import com.gamdo.app.camera.TiltReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameFeatureCalculatorTest {
    private val calculator = FrameFeatureCalculator()

    @Test
    fun `no detections return safe defaults`() {
        val result = calculator.calculate(FrameFeatureInput(DetectionResult(emptyList(), null)))

        assertNull(result.personBox)
        assertNull(result.faceBox)
        assertEquals(0f, result.personAreaRatio, 0.0001f)
        assertEquals(0f, result.headroom, 0.0001f)
        assertFalse(result.backlightFlag)
        assertTrue(result.lowLightFlag)
    }

    @Test
    fun `pose landmarks create a clamped person box`() {
        val pose = PoseObservation(
            landmarks = listOf(
                PoseLandmarkPoint(0, 0.2f, 0.25f, 0.9f),
                PoseLandmarkPoint(1, 0.8f, 0.75f, 0.9f),
                PoseLandmarkPoint(2, 1.2f, -0.1f, 0.9f),
            ),
            averageInFrameLikelihood = 0.8f,
        )
        val result = calculator.calculate(FrameFeatureInput(DetectionResult(emptyList(), pose)))

        assertEquals(NormalizedBox(0.2f, 0f, 1f, 0.75f), result.personBox)
        assertEquals(0.8f * 0.75f, result.personAreaRatio, 0.0001f)
    }

    @Test
    fun `low likelihood landmarks are excluded from person box`() {
        val pose = PoseObservation(
            landmarks = listOf(
                PoseLandmarkPoint(0, 0.1f, 0.1f, 0.2f),
                PoseLandmarkPoint(1, 0.4f, 0.4f, 0.8f),
            ),
            averageInFrameLikelihood = 0.5f,
        )
        val result = calculator.calculate(FrameFeatureInput(DetectionResult(emptyList(), pose)))

        assertEquals(NormalizedBox(0.4f, 0.4f, 0.4f, 0.4f), result.personBox)
    }

    @Test
    fun `face is used when pose is unavailable`() {
        val face = FaceObservation(NormalizedBox(0.3f, 0.2f, 0.5f, 0.5f), null, null, 0f)
        val result = calculator.calculate(FrameFeatureInput(DetectionResult(listOf(face), null)))

        assertEquals(face.box, result.personBox)
        assertEquals(face.box, result.faceBox)
        assertEquals(0.2f, result.headroom, 0.0001f)
    }

    @Test
    fun `larger central face wins over smaller edge face`() {
        val edge = FaceObservation(NormalizedBox(0.02f, 0.1f, 0.22f, 0.3f), null, null, 0f)
        val central = FaceObservation(NormalizedBox(0.4f, 0.3f, 0.7f, 0.65f), null, null, 0f)
        val result = calculator.calculate(FrameFeatureInput(DetectionResult(listOf(edge, central), null)))

        assertEquals(central.box, result.faceBox)
    }

    @Test
    fun `multiple person candidates use area and centrality`() {
        val edgeLarge = NormalizedBox(0.0f, 0.0f, 0.7f, 0.7f)
        val centralSmall = NormalizedBox(0.35f, 0.35f, 0.65f, 0.65f)
        val result = calculator.calculate(
            FrameFeatureInput(
                detection = DetectionResult(emptyList(), null),
                personCandidates = listOf(edgeLarge, centralSmall),
                brightness = BrightnessSample(0.5f),
            ),
        )

        assertEquals(edgeLarge, result.personBox)
    }

    @Test
    fun `headroom and side margins come from the person and face`() {
        val face = FaceObservation(NormalizedBox(0.25f, 0.12f, 0.45f, 0.35f), null, null, 0f)
        val person = NormalizedBox(0.1f, 0.2f, 0.8f, 0.95f)
        val result = calculator.calculate(
            FrameFeatureInput(
                detection = DetectionResult(listOf(face), null),
                personCandidates = listOf(person),
                brightness = BrightnessSample(0.5f),
            ),
        )

        assertEquals(0.12f, result.headroom, 0.0001f)
        assertEquals(0.1f, result.sideMargins.left, 0.0001f)
        assertEquals(0.2f, result.sideMargins.right, 0.0001f)
    }

    @Test
    fun `backlight is true only when background is 1 point 8 times brighter`() {
        val face = BrightnessSample(frameMean = 0.5f, faceMean = 0.2f, backgroundMean = 0.36f)
        val notBacklit = BrightnessSample(frameMean = 0.5f, faceMean = 0.2f, backgroundMean = 0.35f)

        assertTrue(calculator.calculate(FrameFeatureInput(DetectionResult(emptyList(), null), brightness = face)).backlightFlag)
        assertFalse(calculator.calculate(FrameFeatureInput(DetectionResult(emptyList(), null), brightness = notBacklit)).backlightFlag)
    }

    @Test
    fun `low light and brightness are normalized`() {
        val result = calculator.calculate(
            FrameFeatureInput(
                detection = DetectionResult(emptyList(), null),
                brightness = BrightnessSample(frameMean = 1.5f),
            ),
        )

        assertEquals(1f, result.brightnessMean, 0.0001f)
        assertFalse(result.lowLightFlag)
    }

    @Test
    fun `sensor values and pose confidence are forwarded`() {
        val pose = PoseObservation(emptyList(), 1.4f)
        val result = calculator.calculate(
            FrameFeatureInput(
                detection = DetectionResult(emptyList(), pose),
                tilt = TiltReading(rollDeg = -4.5f, pitchDeg = 7.25f),
                brightness = BrightnessSample(0.5f),
                shake = -1f,
            ),
        )

        assertEquals(-4.5f, result.tiltDeg, 0.0001f)
        assertEquals(7.25f, result.pitchDeg, 0.0001f)
        assertEquals(1f, result.poseConfidence, 0.0001f)
        assertEquals(0f, result.shake, 0.0001f)
    }

    @Test
    fun `configured thresholds are honored`() {
        val configured = FrameFeatureCalculator(lowLightThreshold = 0.4f, backlightRatioThreshold = 2.0f)
        val result = configured.calculate(
            FrameFeatureInput(
                detection = DetectionResult(emptyList(), null),
                brightness = BrightnessSample(frameMean = 0.3f, faceMean = 0.2f, backgroundMean = 0.39f),
            ),
        )

        assertTrue(result.lowLightFlag)
        assertFalse(result.backlightFlag)
    }
}
