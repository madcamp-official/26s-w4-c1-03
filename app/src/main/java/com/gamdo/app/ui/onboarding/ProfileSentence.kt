package com.gamdo.app.ui.onboarding

/**
 * Turns [com.gamdo.app.data.ProfileEngine]'s summary into the one sentence 시안 02 shows.
 *
 * ## What is here and what deliberately is not
 *
 * The engine produces noun phrases measured from the user's picks and joins them with
 * commas — `밝은 자연광, 여백이 넓은 구도, 부드러운 색감`. 시안 02 wants a sentence:
 * *"밝은 자연광과 부드러운 색감, 꾸미지 않은 순간을 좋아하시네요."*
 *
 * **No phrase is written here.** Every describing word still comes from the engine's
 * measurement, so two different selections still read differently — that is the property
 * that died when the palette was three constants, and a hard-coded sentence would kill it
 * again in the copy instead of in the colour. All this adds is the joining and the two
 * particles, and it is in the UI layer because particle agreement is a rendering concern
 * (the same class of thing as pluralisation) and because `data/ProfileEngine.kt` is
 * read-only to this screen.
 *
 * ## Why the particles need code
 *
 * Korean chooses 와/과 and 을/를 by whether the preceding syllable ends in a consonant.
 * `색감` takes 을, `순간` takes 을, `자연광` takes 과, `구도` takes 와. Picking one form
 * and shipping it would misread roughly half the engine's phrases — and which half depends
 * on what the user picked, so it would be wrong for some users and right for others.
 *
 * Pure Kotlin, `android.*` import 0.
 */
object ProfileSentence {

    /** Start of the Hangul syllables block. */
    private const val HANGUL_FIRST = 0xAC00

    /** End of the Hangul syllables block. */
    private const val HANGUL_LAST = 0xD7A3

    /** Every syllable has one of 28 finals, index 0 being "no final consonant". */
    private const val FINAL_FORMS = 28

    /**
     * True when [text] ends in a consonant, which is what both particle choices turn on.
     *
     * Returns false for anything that is not a Hangul syllable — a digit, a latin letter,
     * an empty string. The engine only ever produces Hangul here, so this is the
     * defensive branch rather than a case being handled; guessing 과/을 for a non-Hangul
     * tail would be a coin flip either way, and the vowel form is the one that reads less
     * broken when it is wrong.
     */
    fun endsInConsonant(text: String): Boolean {
        val last = text.trim().lastOrNull()?.code ?: return false
        if (last < HANGUL_FIRST || last > HANGUL_LAST) return false
        return (last - HANGUL_FIRST) % FINAL_FORMS != 0
    }

    /** `과` after a consonant, `와` after a vowel. */
    fun andParticle(afterPhrase: String): String = if (endsInConsonant(afterPhrase)) "과" else "와"

    /** `을` after a consonant, `를` after a vowel. */
    fun objectParticle(afterPhrase: String): String = if (endsInConsonant(afterPhrase)) "을" else "를"

    /**
     * 시안 02's summary sentence, built from the engine's comma-separated phrases.
     *
     * The shape follows the design's own line: the first phrases are bound with 와/과 and a
     * comma, and the last one takes the object particle before 좋아하시네요.
     *
     *  - three phrases → `A과 B, C을 좋아하시네요.`
     *  - two           → `A과 B를 좋아하시네요.`
     *  - one           → `A을 좋아하시네요.`
     *  - none          → null, so the caller shows nothing rather than a sentence with a
     *    hole in it. The screen has its own words for "the profile could not be built";
     *    inventing a preference here would be the failure AGENTS §7-6 names.
     */
    fun from(engineSummary: String?): String? {
        val phrases = engineSummary
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        if (phrases.isEmpty()) return null

        val last = phrases.last()
        val lead = phrases.dropLast(1)
        val head = when {
            lead.isEmpty() -> ""
            // Binding every phrase with 과 — `A과 B과 C을` — reads as a list that forgot
            // to end. The design's line binds one pair and commas the rest, so 과 goes on
            // the second-to-last leading phrase and the others are separated by commas.
            else -> {
                val bound = lead.last()
                val commaed = lead.dropLast(1)
                if (commaed.isEmpty()) {
                    bound + andParticle(bound) + " "
                } else {
                    val pivot = commaed.last()
                    commaed.dropLast(1).joinToString("") { "$it, " } +
                        pivot + andParticle(pivot) + " " + bound + ", "
                }
            }
        }
        return head + last + objectParticle(last) + " 좋아하시네요."
    }
}
