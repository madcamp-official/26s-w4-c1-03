package com.gamdo.app.data

import com.gamdo.app.data.preset.ColorParams
import com.gamdo.app.data.preset.Composition
import com.gamdo.app.data.preset.StylePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileEngineTest {
    private val presets = listOf(
        PresetProfile("bright_review", mapOf("subjectScale" to .6f, "subjectPosition" to .5f, "headroom" to .05f, "backgroundRatio" to .4f, "framing" to .8f), mapOf("brightness" to .85f, "colorTemperature" to 6200f, "saturation" to .65f, "contrast" to .65f, "sharpness" to .8f, "grain" to 0f, "candidness" to .25f)),
        PresetProfile("soft_film", mapOf("subjectScale" to .42f, "subjectPosition" to .66f, "headroom" to .12f, "backgroundRatio" to .65f, "framing" to .35f), mapOf("brightness" to .45f, "colorTemperature" to 5200f, "saturation" to .4f, "contrast" to .35f, "sharpness" to .45f, "grain" to .7f, "candidness" to .7f)),
        PresetProfile("candid_feed", mapOf("subjectScale" to .4f, "subjectPosition" to .33f, "headroom" to .1f, "backgroundRatio" to .6f, "framing" to .55f), mapOf("brightness" to .6f, "colorTemperature" to 5500f, "saturation" to .5f, "contrast" to .5f, "sharpness" to .55f, "grain" to .25f, "candidness" to .85f)),
    )

    private fun card(
        id: String,
        bright: Float,
        background: Float,
        temperature: Float,
        subjectScale: Float = .42f,
        subjectPosition: Float = .66f,
        headroom: Float = .12f,
        framing: Float = .4f,
    ) = CardFeature(
        id, subjectScale, subjectPosition, headroom, background, bright, "natural", temperature, .42f, .38f, .5f, .5f, .72f, framing,
    )

    @Test fun `different card sets produce different recommendations`() {
        val bright = ProfileEngine.build(
            listOf(card("bright", .9f, .35f, 6200f, subjectScale = .6f, subjectPosition = .5f, headroom = .05f, framing = .8f)),
            presets,
        )
        val film = ProfileEngine.build(listOf(card("film", .35f, .7f, 5100f)), presets)
        assertNotEquals(bright.recommendedPresetIds, film.recommendedPresetIds)
        // Was `contains("밝은")`, which pinned one phrasing rather than the fact.
        // The light line now also carries the colour temperature, so a bright card
        // at 6200K reads "밝고 서늘한 빛" — still bright, and the assertion that
        // broke was the wording, not the behaviour.
        assertTrue("bright picks must read as bright, was: ${bright.summary}", bright.summary.startsWith("밝"))
        assertTrue("dim picks must not read as bright, was: ${film.summary}", !film.summary.startsWith("밝"))
    }

    @Test fun `confidence drops when selected cards conflict`() {
        val result = ProfileEngine.build(listOf(card("a", .2f, .2f, 4500f), card("b", .8f, .8f, 6500f)), presets)
        assertTrue(result.color.getValue("brightness").confidence < 1f)
    }

    @Test fun `color feedback changes only color dimensions except natural framing`() {
        val original = ProfileEngine.build(listOf(card("a", .6f, .5f, 5800f)), presets)
        val updated = ProfileEngine.applyFeedback(original, FeedbackSignal.COMPOSITION_GOOD_COLOR_BAD)
        assertEquals(original.composition, updated.composition)
        assertTrue(updated.color.getValue("colorTemperature").mean < original.color.getValue("colorTemperature").mean)
    }

    @Test fun `recommendation distance normalizes kelvin before ranking`() {
        val selected = card("selected", .8f, .7f, 6200f)
        val compositionMatch = PresetProfile(
            "composition_match",
            mapOf("subjectScale" to .42f, "subjectPosition" to .66f, "headroom" to .12f, "backgroundRatio" to .7f, "framing" to .4f),
            mapOf("brightness" to .8f, "colorTemperature" to 4200f, "saturation" to .42f, "contrast" to .38f, "sharpness" to .5f, "grain" to .5f, "candidness" to .72f),
        )
        val temperatureMatch = PresetProfile(
            "temperature_match",
            mapOf("subjectScale" to 0f, "subjectPosition" to 0f, "headroom" to 0f, "backgroundRatio" to 0f, "framing" to 0f),
            mapOf("brightness" to 0f, "colorTemperature" to 6200f, "saturation" to 0f, "contrast" to 0f, "sharpness" to 0f, "grain" to 0f, "candidness" to 0f),
        )

        val result = ProfileEngine.build(listOf(selected), listOf(temperatureMatch, compositionMatch))

        assertEquals("composition_match", result.recommendedPresetIds.first())
    }

    @Test fun `preset contract projects consistently into profile feature space`() {
        val preset = StylePreset(
            id = "candid_feed",
            name = "Candid Feed",
            displayName = "자연스러운 피드",
            composition = Composition(
                targetAspectRatio = "4:5",
                subjectScaleRange = listOf(0.3, 0.5),
                subjectPosition = "third_left",
                headroomRange = listOf(0.06, 0.14),
                horizonPosition = 0.55,
                cameraPitchRange = listOf(-6.0, 6.0),
                posePattern = "candid_motion",
                backgroundRatio = listOf(0.45, 0.65),
            ),
            color = ColorParams(5500.0, 0.15, 0.05, 0.0, 0.08, 0.05, 0.0, 0.12),
        )

        val result = preset.toPresetProfile()

        assertEquals(1f / 3f, result.composition.getValue("subjectPosition"), 0.001f)
        assertEquals(0.4f, result.composition.getValue("subjectScale"), 0.001f)
        assertEquals(5500f, result.color.getValue("colorTemperature"), 0.001f)

        // `candidness` and `framing` are deliberately absent. This test used to assert
        // `candidness == 0.85` against a second `toPresetProfile()` that lived in
        // ProfileEngine.kt and filled both dimensions from proxies (`posePattern` and
        // `1 - cropFreedom`). The wave-0 lead ruling replaced that with the mapper in
        // PresetProfileMapper.kt, which omits any dimension with no defensible
        // counterpart — `recommend()` reads a missing key as zero distance for every
        // preset equally, whereas a guessed value tilts the ranking arbitrarily.
        //
        // Both copies then sat in the tree, compiled only by Kotlin's incremental
        // cache, each with a test asserting the opposite behaviour. Removing the
        // superseded copy is what finally surfaced the disagreement.
        assertTrue("candidness must not be invented", "candidness" !in result.color)
        assertTrue("framing must not be invented", "framing" !in result.composition)
    }
}
