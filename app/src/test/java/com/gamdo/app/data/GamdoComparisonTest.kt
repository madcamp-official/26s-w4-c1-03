package com.gamdo.app.data

import com.gamdo.app.data.preset.ColorParams
import org.junit.Assert.assertTrue
import org.junit.Test

class GamdoComparisonTest {
    @Test fun `comparison keeps the demo profile separate from user profile`() {
        val current = GamdoProfileV2(global = GamdoPolicy(color = ColorParams(6500.0, 0.0, -0.1, -0.2, 0.0, 0.0, 0.0, 0.0)), updatedAt = 1)
        val comparison = GamdoComparisonEngine.compare(current, SceneContext.GENERAL)
        assertTrue(comparison.demoOnly)
        assertTrue(comparison.user.color.colorTemperature != comparison.demo.color.colorTemperature)
    }
}
