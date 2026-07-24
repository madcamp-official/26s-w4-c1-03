package com.gamdo.app.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideConfigJsonTest {
    @Test
    fun `config json maps all tuning values into engine config`() {
        val config = parseGuideConfig(
            """
            {
              "version": 1,
              "smoothingWindow": 7,
              "alignedIouThreshold": 0.65,
              "recomputeMovementThreshold": 0.12,
              "minPoseConfidence": 0.4,
              "maxUnstableFrames": 4
            }
            """.trimIndent(),
        )

        assertEquals(7, config.smoothingWindow)
        assertEquals(0.65f, config.alignedIouThreshold, 0.0001f)
        assertEquals(0.12f, config.recomputeMovementThreshold, 0.0001f)
        assertEquals(0.4f, config.minPoseConfidence, 0.0001f)
        assertEquals(4, config.maxUnstableFrames)
    }

    @Test
    fun `projection keeps visual fields and excludes internal metrics`() {
        val state = OverlayState(
            targetFrame = RectN(0.1f, 0.2f, 0.8f, 0.9f),
            silhouette = SilhouetteSpec(RectN(0.2f, 0.3f, 0.7f, 0.8f)),
            horizonY = 0.5f,
            visible = true,
            aligned = true,
        )

        val projection = state.toProjection()

        assertEquals(state.targetFrame, projection.targetFrame)
        assertEquals(state.silhouette?.bounds, projection.silhouetteBounds)
        assertTrue(projection.visible)
        assertTrue(projection.aligned)
    }
}
