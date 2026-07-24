package com.gamdo.app.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProblemDiagnoserTest {
    private val diagnoser = ProblemDiagnoser()

    @Test
    fun `tilt is diagnosed with severity based on angle`() {
        val problems = diagnoser.diagnose(metrics(tilt = 10f))

        assertEquals(ProblemSeverity.HIGH, problems.one(ProblemCode.TILT).severity)
    }

    @Test
    fun `dark frame and clipped shadows produce underexposed problem`() {
        val problems = diagnoser.diagnose(metrics(brightness = 0.12f, shadowClip = 0.35f))

        assertEquals(ProblemSeverity.MEDIUM, problems.one(ProblemCode.UNDEREXPOSED).severity)
    }

    @Test
    fun `bright frame and clipped highlights produce overexposed problem`() {
        val problems = diagnoser.diagnose(metrics(brightness = 0.98f, highlightClip = 0.55f))

        assertEquals(ProblemSeverity.HIGH, problems.one(ProblemCode.OVEREXPOSED).severity)
    }

    @Test
    fun `low laplacian variance is blur suspect`() {
        val problems = diagnoser.diagnose(metrics(variance = 20f))

        assertEquals(ProblemCode.BLUR_SUSPECT, problems.single().code)
    }

    @Test
    fun `large side margins are diagnosed`() {
        val problems = diagnoser.diagnose(metrics(left = 0.42f, right = 0.3f, variance = 200f))

        assertEquals(ProblemCode.EXCESS_MARGIN, problems.single().code)
        assertTrue(problems.single().value >= 0.7f)
    }

    @Test
    fun `frame feature backlight flag is accepted without exposing a ratio`() {
        val features = FrameFeatures(
            personBox = null,
            faceBox = null,
            personCenter = null,
            personAreaRatio = 0f,
            headroom = 0f,
            sideMargins = SideMargins(0f, 0f),
            tiltDeg = 0f,
            pitchDeg = 0f,
            brightnessMean = 0.5f,
            backlightFlag = true,
            lowLightFlag = false,
            poseConfidence = 0f,
            shake = 0f,
        )

        val problems = diagnoser.diagnose(metrics(variance = 200f), features)

        assertEquals(ProblemCode.BACKLIGHT, problems.single().code)
        assertEquals(0f, problems.single().value)
    }

    private fun metrics(
        tilt: Float = 0f,
        brightness: Float = 0.5f,
        shadowClip: Float = 0f,
        highlightClip: Float = 0f,
        variance: Float = 200f,
        left: Float = 0.2f,
        right: Float = 0.2f,
        backlight: Float? = null,
    ) = ImageMetrics(
        tiltDeg = tilt,
        brightnessMean = brightness,
        shadowClipRatio = shadowClip,
        highlightClipRatio = highlightClip,
        laplacianVariance = variance,
        leftMargin = left,
        rightMargin = right,
        backlightRatio = backlight,
    )

    private fun List<Problem>.one(code: ProblemCode): Problem = first { it.code == code }
}
