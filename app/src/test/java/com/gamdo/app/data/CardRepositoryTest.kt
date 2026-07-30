package com.gamdo.app.data

import com.gamdo.app.ui.onboarding.CardTone
import com.gamdo.app.ui.onboarding.ProfilePalette
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
     * The declared tone must be the tone of the actual pixels.
     *
     * **This is the gap the spread test above left open.** A card set can vary on
     * every declared dimension and still be one photograph repeated, or sixteen
     * photographs whose numbers describe some other sixteen — and that is not
     * hypothetical: until 2026-07-30 all sixteen images were close-up portraits
     * while `cards.json` described a range from `subjectScale` 0.08 to 0.95. Every
     * assertion in this file passed throughout, because none of them opened the
     * images. Someone picking "the wide overcast one" was shown a face.
     *
     * Only the photometric axes are checkable here — brightness, saturation and
     * contrast are functions of the pixels alone. `subjectScale`, `headroom` and
     * `framing` are semantic and stay unverified; see
     * `docs/감도_카드_에셋_라이선스_기록.md` for why measuring them was tried and
     * abandoned.
     *
     * Tolerances are loose on purpose. The point is to catch a card whose numbers
     * belong to a different photograph, not to re-derive them.
     */
    @Test
    fun `every row names the image it was measured from`() {
        // Decoding the JPEG and re-deriving brightness here would be the direct
        // check, and it is not available: unit tests compile against `android.jar`,
        // which has no `javax.imageio` and no working `BitmapFactory`. Same
        // constraint that shapes the rest of this module — no androidTest source
        // set, no Robolectric.
        //
        // So this pins the weaker property that still catches the real failure:
        // the numbers must belong to *this* file. Swap a photo without re-measuring
        // and the hash stops matching.
        val parsed = json.decodeFromString<CardsFile>(cardsJsonText)
        val digest = MessageDigest.getInstance("SHA-256")
        for (card in parsed.cards) {
            val file = File(cardsDir, "${card.id}.jpg")
            val actual = digest.digest(file.readBytes())
                .joinToString("") { "%02x".format(it) }
                .take(16)
            assertTrue(
                "${card.id} has no measuredFrom — its numbers cannot be tied to any image",
                card.measuredFrom.isNotBlank(),
            )
            assertEquals(
                "${card.id}: cards.json was measured from an image with hash " +
                    "${card.measuredFrom} but ${file.name} hashes to $actual. The photo " +
                    "was replaced without re-measuring its row, so the picker is " +
                    "describing a different picture than the one it shows.",
                card.measuredFrom,
                actual,
            )
        }
    }

    /**
     * Two different tastes must produce two different profiles.
     *
     * This is the onboarding's whole purpose stated as a test: the user picks five
     * of sixteen, and what they picked has to change the answer. With the old set
     * it could not — sixteen portraits under different lighting meant every
     * selection landed on the same composition profile, so the screen asked a
     * question whose answer was fixed before it was asked.
     *
     * The two selections are chosen by the data rather than hard-coded, so this
     * keeps testing the *current* bundle: the five darkest against the five
     * brightest. If a future set cannot separate even those, it cannot separate
     * anything a user would do.
     */
    @Test
    fun `a dark selection and a bright selection produce different profiles`() {
        val byBrightness = cards().sortedBy { it.brightness }
        val dark = byBrightness.take(5)
        val bright = byBrightness.takeLast(5)
        val presets = emptyList<PresetProfile>()

        val darkProfile = ProfileEngine.build(dark, presets)
        val brightProfile = ProfileEngine.build(bright, presets)

        val darkMean = darkProfile.color.getValue("brightness").mean
        val brightMean = brightProfile.color.getValue("brightness").mean
        assertTrue(
            "the darkest five and the brightest five differ by only " +
                "${"%.2f".format(brightMean - darkMean)} in brightness — the picker " +
                "cannot tell these tastes apart",
            brightMean - darkMean >= 0.20f,
        )
        assertTrue(
            "both selections summarise identically (\"${darkProfile.summary}\"), so " +
                "the onboarding tells every user the same thing",
            darkProfile.summary != brightProfile.summary,
        )
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

    /**
     * Every card must declare the colour it actually is.
     *
     * `colorTemperature` is not that: it is a point on the Planckian locus, an
     * orange-to-blue line, and a forest photograph has no meaningful position on it.
     * Building the onboarding swatches from it meant a user who picked green
     * photographs was shown grey (owner report, 2026-07-30). `colorA`/`colorB` are the
     * measured CIELAB opponent coordinates that replaced it.
     *
     * Nullable in the model so an older bundle parses; required here so a shipped one
     * cannot omit it. The values themselves are tied to the image bytes by
     * `measuredFrom`, checked above.
     */
    @Test
    fun `every card declares a measured opponent colour`() {
        decodeCardEntries(cardsJsonText, json).forEach { entry ->
            val a = entry.colorA
            val b = entry.colorB
            assertTrue(
                "${entry.feature.id} has no measured colour — the swatches cannot " +
                    "describe a photograph whose colour was never recorded",
                a != null && b != null,
            )
            assertTrue("${entry.feature.id}.colorA out of CIELAB range: $a", a!! in -128f..127f)
            assertTrue("${entry.feature.id}.colorB out of CIELAB range: $b", b!! in -128f..127f)
        }
    }

    /**
     * The deck must contain disagreement on both opponent axes.
     *
     * This is the deck-side half of the reachability guard in `ProfilePaletteTest`.
     * The palette can render any hue, but that is worth nothing if every bundled
     * photograph leans the same way: the onboarding would be asking a question whose
     * answer is fixed by the asset choice rather than by the user. A future curation
     * pass that quietly replaces the green and blue cards with sixteen warm interiors
     * fails here.
     */
    @Test
    fun `the deck spans both opponent axes, so a preference can be expressed`() {
        val entries = decodeCardEntries(cardsJsonText, json)
        val a = entries.mapNotNull { it.colorA }
        val b = entries.mapNotNull { it.colorB }
        assertTrue("no green card: min a* is ${a.min()}", a.min() <= -4f)
        assertTrue("no warm card: max a* is ${a.max()}", a.max() >= 4f)
        assertTrue("no blue card: min b* is ${b.min()}", b.min() <= -4f)
        assertTrue("no yellow card: max b* is ${b.max()}", b.max() >= 4f)
    }

    /**
     * End to end, through the real asset: two tastes must produce two palettes, and
     * the green one must be green.
     *
     * `ProfilePaletteTest` pins the same property against measurements typed into the
     * test. This one reads `cards.json`, so it also fails if the deck's numbers are
     * edited into agreement — the failure mode the unit test cannot see.
     */
    @Test
    fun `a green selection and a warm selection produce different palettes from the bundle`() {
        val entries = decodeCardEntries(cardsJsonText, json)
            .filter { it.colorA != null && it.colorB != null }
        fun tones(ids: List<String>) = entries.filter { it.feature.id in ids }
            .map { CardTone(it.feature.brightness, it.colorA!!, it.colorB!!) }

        val byGreen = entries.sortedBy { it.colorA }
        val greenest = tones(byGreen.take(3).map { it.feature.id })
        val warmest = tones(byGreen.takeLast(3).map { it.feature.id })

        val greenPalette = ProfilePalette.swatches(greenest)
        val warmPalette = ProfilePalette.swatches(warmest)

        assertNotEquals(greenPalette, warmPalette)
        greenPalette.forEach { c ->
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            assertTrue(
                "the three greenest cards in the bundle still produce " +
                    "#%02X%02X%02X, which is not green".format(r, g, b),
                g > r && g > b,
            )
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
