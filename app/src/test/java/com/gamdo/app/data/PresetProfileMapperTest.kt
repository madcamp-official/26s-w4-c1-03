package com.gamdo.app.data

import com.gamdo.app.data.preset.StylePreset
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies `StylePreset.toPresetProfile()` against the lead's wave-0 ruling: map
 * dimensions with a real correspondence, derive dimensions with a documented
 * physical justification (clamped to 0..1), and omit dimensions with none
 * (`framing`, `candidness`) rather than inventing a value for them.
 *
 * Expected numbers below are hand-computed from the live `assets/presets.json`
 * bundle (read straight off disk, same technique `harness/P2ValueDumpTest.kt`
 * uses to avoid needing an Android `Context` under plain `testDebugUnitTest`).
 */
class PresetProfileMapperTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val presets: List<StylePreset> =
        json.decodeFromString(File("src/main/assets/presets.json").readText())

    private fun preset(id: String) = presets.first { it.id == id }.toPresetProfile()

    @Test
    fun `bundled presets json still holds the 6 agreed presets`() {
        assertEquals(6, presets.size)
    }

    @Test
    fun `framing and candidness are omitted for every preset, not invented`() {
        presets.forEach { preset ->
            val profile = preset.toPresetProfile()
            assertFalse("${preset.id}: framing must be omitted", profile.composition.containsKey("framing"))
            assertFalse("${preset.id}: candidness must be omitted", profile.color.containsKey("candidness"))
        }
    }

    @Test
    fun `mapped dimensions are range midpoints or pass-through, unchanged`() {
        val cleanSocial = preset("clean_social")
        // subjectScaleRange [0.35, 0.55] -> mid 0.45
        assertEquals(0.45f, cleanSocial.composition.getValue("subjectScale"), 1e-6f)
        // headroomRange [0.05, 0.12] -> mid 0.085
        assertEquals(0.085f, cleanSocial.composition.getValue("headroom"), 1e-6f)
        // backgroundRatio [0.4, 0.6] -> mid 0.5
        assertEquals(0.5f, cleanSocial.composition.getValue("backgroundRatio"), 1e-6f)
        // subjectPosition "center" -> 0.5
        assertEquals(0.5f, cleanSocial.composition.getValue("subjectPosition"), 1e-6f)
        // colorTemperature passes through natively (Kelvin, no transform)
        assertEquals(5800f, cleanSocial.color.getValue("colorTemperature"), 1e-6f)
        // grain passes through natively (same 0..1 "amount" field on both sides)
        assertEquals(0.0f, cleanSocial.color.getValue("grain"), 1e-6f)

        val candidFeed = preset("candid_feed")
        assertEquals("third_left -> 1/3", 1f / 3f, candidFeed.composition.getValue("subjectPosition"), 1e-6f)
        assertEquals(0.08f, candidFeed.color.getValue("grain"), 1e-6f)

        val softFilm = preset("soft_film")
        assertEquals("third_right -> 2/3", 2f / 3f, softFilm.composition.getValue("subjectPosition"), 1e-6f)
    }

    @Test
    fun `derived dimensions use baseline plus delta and clamp to 0 to 1`() {
        // soft_film: contrast -0.05 -> 0.45, saturation -0.05 -> 0.45
        val softFilm = preset("soft_film")
        assertEquals(0.45f, softFilm.color.getValue("contrast"), 1e-6f)
        assertEquals(0.45f, softFilm.color.getValue("saturation"), 1e-6f)
        assertEquals(0.6f, softFilm.color.getValue("brightness"), 1e-6f) // 0.5 + 0.1 exposureBias

        // casual_portrait: blurStrength 0.1 -> sharpness 0.9
        val casualPortrait = preset("casual_portrait")
        assertEquals(0.9f, casualPortrait.color.getValue("sharpness"), 1e-6f)

        // bright_review: exposureBias 0.6 -> 0.5+0.6=1.1, must clamp to 1.0
        val brightReview = preset("bright_review")
        assertEquals(1.0f, brightReview.color.getValue("brightness"), 1e-6f)
        assertTrue(brightReview.color.getValue("brightness") <= 1f)
    }

    /**
     * This started life as a canary asserting the *bug*: with an unweighted distance
     * sum in `ProfileEngine.recommend()`, a ~50K colorTemperature gap outweighed a
     * maximal (0..1, extreme-to-extreme) mismatch on every other dimension combined,
     * so two polar-opposite card sets both recommended `night_street` and composition
     * had ~zero influence. Its KDoc said that if it ever failed, that was progress and
     * the test — not the mapper — should be updated.
     *
     * It failed. `ProfileEngine.normalizedDistance` now divides the colorTemperature
     * term by `COLOR_TEMPERATURE_SPAN`, so Kelvin sits on the same 0..1 scale as
     * everything else. The assertion is inverted to match: identical colour, opposite
     * composition must now produce **different** recommendations. If this ever goes
     * back to asserting equality, the normalization has been lost.
     */
    @Test
    fun `composition changes the recommendation once colorTemperature is normalized`() {
        val presetProfiles = presets.map { it.toPresetProfile() }

        fun card(id: String, subjectScale: Float, subjectPosition: Float, headroom: Float, backgroundRatio: Float) =
            CardFeature(
                id = id,
                subjectScale = subjectScale,
                subjectPosition = subjectPosition,
                headroom = headroom,
                backgroundRatio = backgroundRatio,
                brightness = 0.5f,
                lightType = "daylight",
                colorTemperature = 4550f, // ~50K from night_street (4600), ~600K+ from every other preset
                saturation = 0.5f,
                contrast = 0.5f,
                sharpness = 0.5f,
                grain = 0.5f,
                candidness = 0.5f,
                framing = 0.5f,
            )

        val tightPortrait = card("synthetic_tight", subjectScale = 0.9f, subjectPosition = 0.1f, headroom = 0.02f, backgroundRatio = 0.05f)
        val wideCandid = card("synthetic_wide", subjectScale = 0.1f, subjectPosition = 0.9f, headroom = 0.5f, backgroundRatio = 0.95f)

        val profileA = ProfileEngine.build(listOf(tightPortrait), presetProfiles)
        val profileB = ProfileEngine.build(listOf(wideCandid), presetProfiles)

        // Colour is identical between the two sets, so any difference in the ranking
        // can only have come from composition.
        assertNotEquals(
            "composition must influence the ranking now that Kelvin is normalized",
            profileA.recommendedPresetIds.first(),
            profileB.recommendedPresetIds.first(),
        )
    }
}
