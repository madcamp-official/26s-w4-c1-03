package com.gamdo.app.data

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

    private fun card(id: String, bright: Float, background: Float, temperature: Float) = CardFeature(
        id, .42f, .66f, .12f, background, bright, "natural", temperature, .42f, .38f, .5f, .5f, .72f, .4f,
    )

    @Test fun `different card sets produce different recommendations`() {
        val bright = ProfileEngine.build(listOf(card("bright", .9f, .35f, 6200f)), presets)
        val film = ProfileEngine.build(listOf(card("film", .35f, .7f, 5100f)), presets)
        assertNotEquals(bright.recommendedPresetIds, film.recommendedPresetIds)
        assertTrue(bright.summary.contains("밝은"))
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
}
