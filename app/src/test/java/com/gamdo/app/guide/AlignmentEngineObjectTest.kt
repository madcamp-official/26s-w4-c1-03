package com.gamdo.app.guide

import com.gamdo.app.detect.FrameFeatures
import com.gamdo.app.detect.NormalizedBox
import com.gamdo.app.detect.SideMargins
import org.junit.Assert.assertTrue
import org.junit.Test

class AlignmentEngineObjectTest {
    @Test
    fun `object box can drive visual alignment without a person box`() {
        val target = StyleTarget(
            subjectScaleRange = 0.35f..0.55f,
            subjectAnchorX = 0.5f,
            subjectAnchorY = 0.5f,
        )
        val objectBox = NormalizedBox(0.32f, 0.085f, 0.68f, 0.535f)
        val state = AlignmentEngine().align(
            features = FrameFeatures(
                personBox = null,
                faceBox = null,
                personCenter = null,
                personAreaRatio = 0f,
                headroom = 0f,
                sideMargins = SideMargins(0f, 0f),
                tiltDeg = 0f,
                pitchDeg = 0f,
                brightnessMean = 0.5f,
                backlightFlag = false,
                lowLightFlag = false,
                poseConfidence = 0f,
                shake = 0f,
            ),
            target = target,
            observedSubjectBox = objectBox,
        )

        assertTrue(state.silhouette != null)
        assertTrue(state.aligned)
    }
}
