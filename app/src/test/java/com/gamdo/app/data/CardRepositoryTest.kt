package com.gamdo.app.data

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the pure [decodeCards] step against the real `assets/cards.json` bundle.
 * `CardRepository.loadBundledCards()` itself needs an Android `Context` (asset open),
 * which is not mockable under plain `testDebugUnitTest` here (no Robolectric/Mockito
 * on the test classpath) — same constraint `harness/P2ValueDumpTest.kt` works around
 * by reading assets straight off disk. That test is the cross-check baseline for this
 * one and stays untouched; this file only asserts the promoted main-code path decodes
 * identically.
 */
class CardRepositoryTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val cardsJsonText = File("src/main/assets/cards.json").readText()

    @Test
    fun `decodes all 16 bundled cards`() {
        val cards = decodeCards(cardsJsonText, json)
        assertEquals(16, cards.size)
        assertEquals(
            (1..16).map { "card_%02d".format(it) },
            cards.map { it.id },
        )
    }

    @Test
    fun `every field survives the round trip for the first card`() {
        val first = decodeCards(cardsJsonText, json).first()
        assertEquals("card_01", first.id)
        assertEquals(0.35f, first.subjectScale, 1e-6f)
        assertEquals(0.33f, first.subjectPosition, 1e-6f)
        assertEquals(0.14f, first.headroom, 1e-6f)
        assertEquals(0.70f, first.backgroundRatio, 1e-6f)
        assertEquals(0.35f, first.brightness, 1e-6f)
        assertEquals("soft", first.lightType)
        assertEquals(4800f, first.colorTemperature, 1e-6f)
        assertEquals(0.35f, first.saturation, 1e-6f)
        assertEquals(0.30f, first.contrast, 1e-6f)
        assertEquals(0.45f, first.sharpness, 1e-6f)
        assertEquals(0.55f, first.grain, 1e-6f)
        assertEquals(0.85f, first.candidness, 1e-6f)
        assertEquals(0.35f, first.framing, 1e-6f)
    }

    @Test
    fun `card ids are unique and feed ProfileEngine without error`() {
        val cards = decodeCards(cardsJsonText, json)
        assertEquals("ids must be unique", cards.size, cards.map { it.id }.distinct().size)

        // Sanity: the promoted CardFeature list is usable by ProfileEngine.build() the
        // same way the harness test uses its private copy — this only checks it doesn't
        // throw, ProfileEngineTest / P2ValueDumpTest own the behavioural assertions.
        val samplePresets = listOf(
            PresetProfile(
                id = "sample",
                composition = mapOf(
                    "subjectScale" to 0.5f, "subjectPosition" to 0.5f, "headroom" to 0.1f,
                    "backgroundRatio" to 0.5f, "framing" to 0.5f,
                ),
                color = mapOf(
                    "brightness" to 0.5f, "colorTemperature" to 5500f, "saturation" to 0.5f,
                    "contrast" to 0.5f, "sharpness" to 0.5f, "grain" to 0.3f, "candidness" to 0.5f,
                ),
            ),
        )
        val profile = ProfileEngine.build(cards.take(5), samplePresets)
        assertTrue(profile.recommendedPresetIds.isNotEmpty())
    }
}
