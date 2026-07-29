package com.gamdo.app.data

import com.gamdo.app.data.preset.ColorParams
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileRefinementEngineTest {
    @Test fun `reference analysis contributes bounded contextual evidence`() {
        val global = GamdoPolicy(color = ColorParams(5000.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0))
        val resolution = ReferenceResolution(
            contentHash = "a".repeat(64), analysisVersion = 3,
            analysis = buildJsonObject { put("peopleCount", 1); put("brightness", 0.2); put("subjectScale", 0.4) },
            targetComposition = buildJsonObject { }, colorTarget = buildJsonObject { put("contrast", 0.8) },
            compositionAvailable = true, colorAvailable = true, fromCache = false,
        )
        val refined = ProfileRefinementEngine.refine(GamdoProfileV2(global = global, updatedAt = 1), listOf(resolution), 2)
        assertTrue(SceneContext.NIGHT_PERSON in refined.contexts)
        assertTrue(refined.policyFor(SceneContext.NIGHT_PERSON).color.contrast > 0.0)
    }
}
