package com.gamdo.app.data.rescue

import com.gamdo.app.data.GamdoPolicy
import com.gamdo.app.data.GamdoProfileV2
import com.gamdo.app.data.SceneContext
import com.gamdo.app.data.preset.ColorParams
import org.junit.Assert.assertEquals
import org.junit.Test

class RescueContextFactoryTest {
    private val global = GamdoPolicy(color = ColorParams(5200.0, 0.0, 0.1, 0.1, 0.0, 0.0, 0.0, 0.0))
    private val night = GamdoPolicy(color = ColorParams(4100.0, 0.2, 0.4, 0.0, 0.0, 0.0, 0.0, 0.0))

    @Test
    fun `scene signals resolve the matching contextual policy`() {
        val profile = GamdoProfileV2(
            global = global,
            contexts = mapOf(SceneContext.NIGHT_PERSON to night),
            updatedAt = 1L,
        )

        val context = RescueContextFactory.fromProfile(
            profile = profile,
            personCount = 1,
            objectLabels = emptySet(),
            brightness = 0.2f,
            subjectScale = 0.5f,
            level = ReinterpretationLevel.REIMAGINE,
        )

        assertEquals(2, context.profileVersion)
        assertEquals(SceneContext.NIGHT_PERSON, context.sceneContext)
        assertEquals(night, context.policy)
        assertEquals(ReinterpretationLevel.REIMAGINE, context.level)
    }

    @Test
    fun `missing profile keeps safe general fallback`() {
        val context = RescueContextFactory.fromProfile(null, SceneContext.CAFE_FOOD, ReinterpretationLevel.MEMORY)

        assertEquals(1, context.profileVersion)
        assertEquals(SceneContext.CAFE_FOOD, context.sceneContext)
        assertEquals(ReinterpretationLevel.MEMORY, context.level)
    }
}
