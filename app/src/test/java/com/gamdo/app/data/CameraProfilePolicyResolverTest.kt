package com.gamdo.app.data

import com.gamdo.app.data.preset.ColorParams
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraProfilePolicyResolverTest {
    private val global = GamdoPolicy(
        capture = CapturePreference(preferredZoom = 1f),
        color = ColorParams(5200.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
    )

    @Test
    fun `night person resolves its contextual capture policy`() {
        val night = global.copy(capture = global.capture.copy(preferredZoom = 2f, flash = FlashPreference.ON))
        val profile = GamdoProfileV2(
            global = global,
            contexts = mapOf(SceneContext.NIGHT_PERSON to night),
            updatedAt = 1L,
        )

        val resolved = CameraProfilePolicyResolver.resolve(
            profile,
            CameraSceneSignals(personCount = 1, brightness = 0.2f, subjectScale = 0.45f),
        )

        assertEquals(SceneContext.NIGHT_PERSON, resolved.context)
        assertEquals(2f, resolved.preferredZoom)
        assertEquals(FlashPreference.ON, resolved.flashPreference)
    }

    @Test
    fun `missing contextual evidence uses global policy`() {
        val profile = GamdoProfileV2(global = global, updatedAt = 1L)

        val resolved = CameraProfilePolicyResolver.resolve(
            profile,
            CameraSceneSignals(personCount = 1, brightness = 0.8f, subjectScale = 0.45f),
        )

        assertEquals(SceneContext.GENERAL, resolved.context)
        assertEquals(global, resolved.policy)
    }
}
