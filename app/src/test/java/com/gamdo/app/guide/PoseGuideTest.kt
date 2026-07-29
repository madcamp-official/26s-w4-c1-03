package com.gamdo.app.guide

import com.gamdo.app.detect.PoseLandmarkPoint
import com.gamdo.app.detect.PoseObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PoseGuideTest {
    private fun pose(types: Set<Int>, seated: Boolean = false): PoseObservation {
        val points = types.map { type ->
            val y = when (type) {
                23, 24 -> 0.5f
                25, 26 -> if (seated) 0.60f else 0.72f
                27, 28 -> 0.9f
                else -> 0.3f
            }
            val x = when (type) {
                11, 23, 25, 27 -> 0.4f
                else -> 0.6f
            }
            PoseLandmarkPoint(type, x, y, 0.9f)
        }
        return PoseObservation(points, 0.9f)
    }

    @Test
    fun `full body chooses fixed full pose`() {
        val selected = PoseGuideSelector.select(pose(setOf(11, 12, 23, 24, 25, 26, 27, 28)))
        assertEquals(PoseGuideCatalog.FULL_CENTER, selected?.id)
    }

    @Test
    fun `missing lower body chooses upper body target`() {
        val selected = PoseGuideSelector.select(pose(setOf(11, 12, 23, 24)))
        assertEquals(PoseGuideCatalog.UPPER_BODY, selected?.id)
    }

    @Test
    fun `missing shoulders falls back outside pose guide`() {
        assertNull(PoseGuideSelector.select(pose(setOf(23, 24, 25, 26, 27, 28))))
    }
}

