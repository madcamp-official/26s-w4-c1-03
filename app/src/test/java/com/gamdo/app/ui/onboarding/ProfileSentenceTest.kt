package com.gamdo.app.ui.onboarding

import com.gamdo.app.data.CardFeature
import com.gamdo.app.data.ProfileEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 시안 02's summary sentence: the engine's measured phrases, joined with the right
 * particles, and **nothing written here**.
 */
class ProfileSentenceTest {

    // ---- particle agreement ------------------------------------------------------

    @Test
    fun `a final consonant is detected`() {
        // 광 · 감 · 간 · 심 end in a consonant; 도 · 기 · 빛 …
        assertTrue(ProfileSentence.endsInConsonant("밝은 자연광"))
        assertTrue(ProfileSentence.endsInConsonant("부드러운 색감"))
        assertTrue(ProfileSentence.endsInConsonant("피사체 중심"))
        assertTrue(ProfileSentence.endsInConsonant("차분한 빛"))
        assertFalse(ProfileSentence.endsInConsonant("여백이 넓은 구도"))
        assertFalse(ProfileSentence.endsInConsonant("균형 잡힌 구도"))
    }

    @Test
    fun `trailing whitespace does not change the particle`() {
        assertEquals("과", ProfileSentence.andParticle("밝은 자연광  "))
        assertEquals("와", ProfileSentence.andParticle("여백이 넓은 구도 "))
    }

    @Test
    fun `non-hangul and empty tails fall back rather than throwing`() {
        assertFalse(ProfileSentence.endsInConsonant(""))
        assertFalse(ProfileSentence.endsInConsonant("preset 3"))
        assertEquals("와", ProfileSentence.andParticle(""))
        assertEquals("를", ProfileSentence.objectParticle(""))
    }

    @Test
    fun `both particles pick the consonant form after a consonant`() {
        assertEquals("과", ProfileSentence.andParticle("색감"))
        assertEquals("와", ProfileSentence.andParticle("구도"))
        assertEquals("을", ProfileSentence.objectParticle("색감"))
        assertEquals("를", ProfileSentence.objectParticle("구도"))
    }

    // ---- the sentence ------------------------------------------------------------

    @Test
    fun `three phrases become the design's sentence shape`() {
        assertEquals(
            "밝은 자연광과 여백이 넓은 구도, 부드러운 색감을 좋아하시네요.",
            ProfileSentence.from("밝은 자연광, 여백이 넓은 구도, 부드러운 색감"),
        )
    }

    @Test
    fun `two phrases bind with the and-particle`() {
        assertEquals(
            "차분한 빛과 여백이 넓은 구도를 좋아하시네요.",
            ProfileSentence.from("차분한 빛, 여백이 넓은 구도"),
        )
    }

    @Test
    fun `one phrase takes only the object particle`() {
        assertEquals("피사체 중심을 좋아하시네요.", ProfileSentence.from("피사체 중심"))
    }

    /**
     * Nothing to say means say nothing. The screen has its own words for a profile that
     * could not be built; producing "…을 좋아하시네요." with a hole in front of it would be
     * a claim about a preference that was never measured.
     */
    @Test
    fun `no phrases produces no sentence`() {
        assertNull(ProfileSentence.from(null))
        assertNull(ProfileSentence.from(""))
        assertNull(ProfileSentence.from("   "))
        assertNull(ProfileSentence.from(", ,"))
    }

    // ---- against the real engine -------------------------------------------------

    private fun card(
        id: String,
        brightness: Float,
        kelvin: Float,
        background: Float,
        saturation: Float,
        contrast: Float,
    ) = CardFeature(
        id = id,
        subjectScale = 0.4f,
        subjectPosition = 0.5f,
        headroom = 0.2f,
        backgroundRatio = background,
        brightness = brightness,
        lightType = "natural",
        colorTemperature = kelvin,
        saturation = saturation,
        contrast = contrast,
        sharpness = 0.5f,
        grain = 0.2f,
        candidness = 0.5f,
        framing = 0.5f,
    )

    private val brightAiry = (1..5).map {
        card("bright_$it", brightness = 0.72f, kelvin = 5500f, background = 0.70f, saturation = 0.30f, contrast = 0.30f)
    }
    private val darkPunchy = (1..5).map {
        card("dark_$it", brightness = 0.10f, kelvin = 3800f, background = 0.20f, saturation = 0.80f, contrast = 0.85f)
    }

    /**
     * The end-to-end property that matters: the sentence is a function of the picks. If
     * this ever passes for two opposite selections producing the same words, the feature is
     * dead even though every unit above still passes.
     */
    @Test
    fun `opposite selections produce different sentences`() {
        val bright = ProfileSentence.from(ProfileEngine.build(brightAiry, emptyList()).summary)
        val dark = ProfileSentence.from(ProfileEngine.build(darkPunchy, emptyList()).summary)
        assertNotEquals(bright, dark)
        assertTrue("bright selection produced no sentence", !bright.isNullOrBlank())
        assertTrue("dark selection produced no sentence", !dark.isNullOrBlank())
    }

    /** Whatever the engine says, the result is one well-formed sentence. */
    @Test
    fun `the engine's own output always assembles cleanly`() {
        for (selection in listOf(brightAiry, darkPunchy)) {
            val sentence = ProfileSentence.from(ProfileEngine.build(selection, emptyList()).summary)!!
            assertTrue("'$sentence' does not end the sentence", sentence.endsWith("좋아하시네요."))
            assertFalse("'$sentence' has a doubled separator", sentence.contains(", ,"))
            assertFalse("'$sentence' has a dangling comma", sentence.contains(",  "))
            // A phrase list that leaked through unjoined would still contain the engine's
            // own comma-space between every pair and no particle at all.
            assertTrue("'$sentence' never binds a phrase", sentence.contains("과 ") || sentence.contains("와 "))
        }
    }
}
