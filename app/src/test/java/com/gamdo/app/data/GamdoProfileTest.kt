package com.gamdo.app.data

import com.gamdo.app.data.preset.ColorParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GamdoProfileTest {
    private val policy = GamdoPolicy(
        color = ColorParams(5500.0, 0.0, 0.1, 0.2, 0.0, 0.0, 0.0, 0.0),
        confidence = 0.5f,
    )

    @Test fun `context resolver uses general when signals are uncertain`() {
        assertEquals(SceneContext.GENERAL, SceneContextResolver.resolve(0, setOf("unknown"), 0.5f, 0.5f))
        assertEquals(SceneContext.NIGHT_PERSON, SceneContextResolver.resolve(1, emptySet(), 0.2f, 0.5f))
        assertEquals(SceneContext.CAFE_FOOD, SceneContextResolver.resolve(0, setOf("cup"), 0.6f, 0.4f))
    }

    @Test fun `evidence merge creates bounded contextual policy without replacing global`() {
        val profile = GamdoProfileV2(global = policy, updatedAt = 1L)
        val candidate = policy.copy(
            capture = policy.capture.copy(preferredZoom = 3f),
            evidence = listOf(PreferenceEvidence(EvidenceSource.IMAGE_ANALYSIS, 1)),
            confidence = 1f,
        )

        val merged = GamdoProfileFactory.mergeEvidence(profile, SceneContext.CAFE_FOOD, candidate, 1f, 2L)

        assertEquals(policy, merged.global)
        assertTrue(merged.policyFor(SceneContext.CAFE_FOOD).capture.preferredZoom <= 2f)
        assertEquals(2L, merged.updatedAt)
    }
}
