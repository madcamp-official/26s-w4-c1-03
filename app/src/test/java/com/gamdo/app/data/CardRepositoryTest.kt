package com.gamdo.app.data

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the pure [decodeCards] step against the real `assets/cards.json` bundle.
 * `CardRepository.loadBundledCards()` itself needs an Android `Context` (asset open),
 * which is not mockable under plain `testDebugUnitTest` here (no Robolectric/Mockito
 * on the test classpath) — same constraint `harness/P2ValueDumpTest.kt` works around
 * by reading assets straight off disk.
 *
 * ## Why this asserts properties and not one card's literal values
 *
 * It used to pin the thirteen numbers of `card_01`. That is a duplicate of the
 * asset, not a check on it: it breaks whenever the photos are retuned, and it was
 * green throughout the defect it should have caught — **16 files holding 6 distinct
 * images**, with the same photo given contradictory features (card_01 "dark film"
 * 0.35/soft, card_07 "bright window" 0.78/window). A user picking a look they liked
 * trained the profile on the opposite one.
 *
 * So this file checks the things that must be true of *any* honest card set,
 * including the image bytes themselves.
 */
class CardRepositoryTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val cardsJsonText = File("src/main/assets/cards.json").readText()
    private val cardsDir = File("src/main/assets/cards")

    private fun cards() = decodeCards(cardsJsonText, json)

    @Test
    fun `decodes all 16 bundled cards`() {
        val cards = cards()
        assertEquals(16, cards.size)
        assertEquals((1..16).map { "card_%02d".format(it) }, cards.map { it.id })
    }

    /** The defect, stated as a test: sixteen cards must be sixteen photographs. */
    @Test
    fun `every card is a distinct image file`() {
        assertTrue("assets/cards/ is missing", cardsDir.isDirectory)
        val files = cards().map { File(cardsDir, "${it.id}.jpg") }
        files.forEach { assertTrue("missing ${it.name}", it.isFile) }

        val digests = files.associate { f ->
            f.name to MessageDigest.getInstance("MD5").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        }
        val duplicates = digests.entries.groupBy { it.value }.filterValues { it.size > 1 }
        assertTrue(
            "duplicated card images: " + duplicates.values.joinToString { g -> g.joinToString("=") { it.key } },
            duplicates.isEmpty(),
        )
    }

    @Test
    fun `every field is inside its domain`() {
        val unit = { name: String, v: Float ->
            assertTrue("$name out of 0..1: $v", v in 0f..1f)
        }
        cards().forEach { c ->
            unit("${c.id}.subjectScale", c.subjectScale)
            unit("${c.id}.subjectPosition", c.subjectPosition)
            unit("${c.id}.headroom", c.headroom)
            unit("${c.id}.backgroundRatio", c.backgroundRatio)
            unit("${c.id}.brightness", c.brightness)
            unit("${c.id}.saturation", c.saturation)
            unit("${c.id}.contrast", c.contrast)
            unit("${c.id}.sharpness", c.sharpness)
            unit("${c.id}.grain", c.grain)
            unit("${c.id}.candidness", c.candidness)
            unit("${c.id}.framing", c.framing)
            assertTrue("${c.id}.lightType unknown: ${c.lightType}", c.lightType in LIGHT_TYPES)
        }
    }

    /**
     * ProfileEngine divides Kelvin distance by `COLOR_TEMPERATURE_SPAN = 2000` and
     * clamps at 1. A card further than 2000 K from every preset contributes the same
     * saturated distance no matter which preset it is compared with, so that axis
     * stops ranking anything. presets.json spans 4600..6200 K.
     */
    @Test
    fun `colour temperatures stay where the recommender can still discriminate`() {
        cards().forEach { c ->
            assertTrue(
                "${c.id} colorTemperature ${c.colorTemperature} is outside the discriminating range",
                c.colorTemperature in 4000f..7000f,
            )
        }
    }

    /** A card set whose values barely move cannot separate one preset from another. */
    @Test
    fun `the set actually varies on every ranked dimension`() {
        val cards = cards()
        fun spread(name: String, get: (CardFeature) -> Float, min: Float) {
            val values = cards.map(get)
            val range = values.max() - values.min()
            assertTrue("$name barely varies across the set (range $range)", range >= min)
        }
        spread("subjectScale", { it.subjectScale }, 0.3f)
        spread("backgroundRatio", { it.backgroundRatio }, 0.3f)
        spread("brightness", { it.brightness }, 0.3f)
        spread("saturation", { it.saturation }, 0.3f)
        spread("contrast", { it.contrast }, 0.3f)
        spread("candidness", { it.candidness }, 0.3f)
        spread("framing", { it.framing }, 0.3f)
        spread("colorTemperature", { it.colorTemperature }, 800f)
    }

    /**
     * `decodeCardEntries` is what the onboarding picker calls instead of re-parsing
     * `cards.json` itself: it must carry the same 16 features as [decodeCards] plus the
     * `thumbnail` path each `PickCard` needs for its `AsyncImage`.
     */
    @Test
    fun `decodeCardEntries pairs every feature with its bundled thumbnail path`() {
        val entries = decodeCardEntries(cardsJsonText, json)
        assertEquals(16, entries.size)
        assertEquals(cards().map { it.id }, entries.map { it.feature.id })
        entries.forEach { entry ->
            assertEquals("cards/${entry.feature.id}.jpg", entry.thumbnail)
        }
    }

    @Test
    fun `card ids are unique and feed ProfileEngine without error`() {
        val cards = cards()
        assertEquals("ids must be unique", cards.size, cards.map { it.id }.distinct().size)

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

    private companion object {
        val LIGHT_TYPES = setOf(
            "soft", "overcast", "daylight", "mixed", "shade",
            "window", "street", "night", "warm_lamp",
        )
    }
}
