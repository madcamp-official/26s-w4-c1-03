package com.gamdo.app.edit

/**
 * Photo filters expressed in **Lightroom's own slider units**, not in some
 * normalised internal space.
 *
 * ## Why the units are Lightroom's
 *
 * Every preset below is a recipe a working photographer actually published, with
 * the numbers they published. Storing `highlights = -100` rather than
 * `highlights = -1.0f` means anyone can open the source article next to this file
 * and check it line by line. The conversion to engine units happens once, in
 * [FilterEngine], where it is written down and tested — not scattered here where a
 * silent rescale would quietly become a different look with the same citation.
 *
 * Ranges match the Lightroom mobile UI:
 *  - `exposureEv` in EV, roughly -5..+5
 *  - everything else -100..+100
 *
 * ## Why these values and not invented ones
 *
 * The filters this replaced were invented: `warmth = 0.08` multiplied by a `22`
 * constant produced **+1.8 of red out of 255**. Below the threshold where anyone
 * can see anything. All six looked identical because all six were, in effect, the
 * identity. Copying real recipes fixes the magnitudes as a side effect — a
 * photographer who writes `어두운 영역 +100` means it.
 *
 * ## The one thing that cannot be copied verbatim: exposure
 *
 * A published preset was tuned against **one photograph**. Tone and colour moves
 * transfer between images; exposure does not. `bright_review` below carries
 * `+2.18 EV` because its author was rescuing a backlit subject — applied blind to
 * an already-bright frame it is a white rectangle. [FilterEngine] therefore treats
 * `exposureEv` as a *ceiling* and caps it by the highlight headroom it measures in
 * the actual image, so the intent ("lift until it reads bright") survives while the
 * literal number does not blow out a photo that never needed it. Every other field
 * is applied exactly as published.
 */
data class PhotoFilter(
    val id: String,
    val label: String,
    val tone: Tone = Tone(),
    val color: Color = Color(),
    val hsl: List<HueAdjust> = emptyList(),
    val effects: Effects = Effects(),
    /** Where the numbers came from. Kept in the type so it cannot drift from them. */
    val credit: String = "",
) {
    /** Lightroom 밝기 탭. */
    data class Tone(
        /** 노출, EV. Treated as a ceiling — see the class KDoc. */
        val exposureEv: Float = 0f,
        /** 대비 */
        val contrast: Int = 0,
        /** 밝은 영역 */
        val highlights: Int = 0,
        /** 어두운 영역 */
        val shadows: Int = 0,
        /** 흰색 계열 — white point, not a highlight push */
        val whites: Int = 0,
        /** 검정 계열 — black point */
        val blacks: Int = 0,
    )

    /** Lightroom 색상 탭. */
    data class Color(
        /** 색온도. Positive is warmer (yellow), negative cooler (blue). */
        val temp: Int = 0,
        /** 색조. Positive is magenta, negative green. */
        val tint: Int = 0,
        /** 생동감 — saturation weighted by how unsaturated a pixel already is. */
        val vibrance: Int = 0,
        /** 채도 — uniform. */
        val saturation: Int = 0,
    )

    /** One row of Lightroom 색상 혼합 (HSL). */
    data class HueAdjust(
        val band: HueBand,
        val saturation: Int = 0,
        val hueShift: Int = 0,
        val luminance: Int = 0,
    )

    /** Lightroom 효과 탭, minus the ones that need a local-contrast pass. */
    data class Effects(
        /** 입자 */
        val grain: Int = 0,
        /** Matte lift. Not a Lightroom slider — the film look's crushed-black fade. */
        val fade: Int = 0,
        /** 비네팅, negative darkens the corners. */
        val vignette: Int = 0,
    )
}

/**
 * Lightroom's eight 색상 혼합 bands, at their nominal hue centres in degrees.
 *
 * The centres are uneven on purpose — they follow Lightroom, which spends four of
 * its eight bands between red and green because that is where skin, foliage and
 * daylight live, and where a two-degree hue error is visible.
 */
enum class HueBand(val centreDeg: Float) {
    RED(0f),
    ORANGE(30f),
    YELLOW(60f),
    GREEN(120f),
    AQUA(180f),
    BLUE(240f),
    PURPLE(280f),
    MAGENTA(320f),
}

/**
 * The six looks, one per style in `presets.json`.
 *
 * Sources are Korean photographers publishing their mobile-Lightroom recipes as
 * slider dumps — the format the owner pointed at ("밝기 얼마, 대비 얼마"). Two are
 * Canon Korea interview features, one is a tutorial by the same publisher.
 *
 * Where a recipe carried 색상 혼합 rows they are kept: those rows are what make the
 * looks distinguishable from each other. Without them `soft_film` and
 * `candid_feed` collapse into "slightly warm, slightly flat" and the style picker
 * stops meaning anything — which is the state this file was written to fix.
 */
object PhotoFilters {

    /** Identity. Present so "원본" is a real entry rather than a special case. */
    val ORIGINAL = PhotoFilter(id = "original", label = "원본")

    /**
     * @byeonnoodle's 청량한 여름 방학 recipe.
     *
     * The ±100 highlight/shadow pair is the signature of this whole genre of
     * Korean feed editing: flatten the tonal range almost completely, then get the
     * separation back from 흰색 계열 and the colour mixer. It reads as "clean" —
     * which is what `clean_social` is for.
     *
     * 노랑 채도 +100 is as published. It is extreme, and it is the point: it is
     * what makes summer light look like summer light in this recipe.
     */
    val CLEAN_SOCIAL = PhotoFilter(
        id = "clean_social",
        label = "깔끔한 소셜",
        tone = PhotoFilter.Tone(
            exposureEv = 0.7f,
            highlights = -100,
            shadows = 100,
            whites = 40,
        ),
        color = PhotoFilter.Color(temp = 5, vibrance = 19, saturation = 7),
        hsl = listOf(
            PhotoFilter.HueAdjust(HueBand.GREEN, saturation = 39, hueShift = -19),
            PhotoFilter.HueAdjust(HueBand.YELLOW, saturation = 100),
        ),
        credit = "@byeonnoodle · 청량한 여름 방학 색감 (Canon Korea)",
    )

    /**
     * @_peppermint.b's 가을 감성사진 recipe.
     *
     * The most restrained of the six — modest everything, 검정 계열 +46 doing most
     * of the work by lifting the shadows into a soft matte. That restraint is why
     * it is mapped to `candid_feed`: the style whose job is to not look edited.
     */
    val CANDID_FEED = PhotoFilter(
        id = "candid_feed",
        label = "자연스러운 피드",
        tone = PhotoFilter.Tone(
            exposureEv = 0.34f,
            contrast = 18,
            highlights = -14,
            shadows = 36,
            whites = 20,
            blacks = 46,
        ),
        color = PhotoFilter.Color(temp = 9, tint = -9, vibrance = 12),
        credit = "@_peppermint.b · 색감클래스 04 가을 감성사진 (Canon Korea)",
    )

    /**
     * @byeonnoodle's 역광사진 살리기 recipe.
     *
     * Built to rescue a backlit subject, which is exactly the failure mode a review
     * photo hits — object against a bright window. 어두운 영역 +100 with 검정 계열
     * **-43** is the interesting pair: lift everything out of the shadows, then put
     * the floor back so it does not turn grey.
     *
     * The +2.18 EV is real and is the reason [FilterEngine] caps exposure by
     * measured headroom rather than applying it literally.
     */
    val BRIGHT_REVIEW = PhotoFilter(
        id = "bright_review",
        label = "밝은 리뷰",
        tone = PhotoFilter.Tone(
            exposureEv = 2.18f,
            highlights = -45,
            shadows = 100,
            whites = 33,
            blacks = -43,
        ),
        color = PhotoFilter.Color(temp = 6, vibrance = 28, saturation = 20),
        hsl = listOf(
            PhotoFilter.HueAdjust(HueBand.BLUE, saturation = 23, luminance = -71),
        ),
        credit = "@byeonnoodle · 역광사진 살리기 (Canon Korea)",
    )

    /**
     * @picn2k's 필름 느낌 tutorial, with the tonal shape filled in from the
     * English-language film-look consensus (contrast -10~-20, highlights -15~-30,
     * shadows +15~+30, whites -10~-20, blacks +10~+20, vibrance -10~-20,
     * saturation -5~-15).
     *
     * The tutorial is explicit about three things and they are all here: 밝은 영역
     * -100, the yellow/blue saturation pair, and grain. The tonal numbers around
     * them are the genre's, not one photograph's, which is what makes them safe to
     * generalise — unlike exposure.
     */
    val SOFT_FILM = PhotoFilter(
        id = "soft_film",
        label = "부드러운 필름",
        tone = PhotoFilter.Tone(
            exposureEv = 0.1f,
            contrast = -12,
            highlights = -100,
            shadows = 22,
            whites = -14,
            blacks = 16,
        ),
        color = PhotoFilter.Color(temp = -8, tint = -6, vibrance = -14, saturation = -8),
        hsl = listOf(
            PhotoFilter.HueAdjust(HueBand.YELLOW, saturation = 43),
            PhotoFilter.HueAdjust(HueBand.BLUE, saturation = 33),
        ),
        effects = PhotoFilter.Effects(grain = 25, fade = 18),
        credit = "@picn2k · 감성적인 필름 느낌 보정법 (Canon Korea) + 필름룩 통용값",
    )

    /**
     * @_peppermint.b's 실내 카페사진 recipe.
     *
     * 대비 **-22** with 검정 계열 **+48** — the only recipe of the six that softens
     * contrast outright. That is the indoor-portrait move: flat light is
     * unflattering when you add contrast to it, and lifted blacks keep skin out of
     * the mud. 색온도 +26 is the strongest warmth here and reads as tungsten.
     */
    val CASUAL_PORTRAIT = PhotoFilter(
        id = "casual_portrait",
        label = "캐주얼 인물",
        tone = PhotoFilter.Tone(
            exposureEv = 0.22f,
            contrast = -22,
            highlights = -20,
            shadows = 13,
            whites = 13,
            blacks = 48,
        ),
        color = PhotoFilter.Color(temp = 26, tint = -2, vibrance = 17),
        credit = "@_peppermint.b · 색감클래스 02 실내 카페사진 (Canon Korea)",
    )

    /**
     * @_peppermint.b's 야경사진 recipe.
     *
     * 색조 **+27** is the one to notice — a magenta push that turns a night sky
     * violet instead of the muddy navy a phone sensor produces. Paired with
     * 생동감 +39, the highest of the six, because city light is narrow-band and
     * comes out of the sensor desaturated.
     *
     * The recipe's 텍스처 +15 is dropped: texture is a local-contrast operator and
     * this engine is global-only. Dropping it is honest; faking it with 대비 would
     * change the look everywhere instead of on edges.
     */
    val NIGHT_STREET = PhotoFilter(
        id = "night_street",
        label = "밤거리",
        tone = PhotoFilter.Tone(
            exposureEv = 0.36f,
            highlights = -52,
            shadows = 46,
            whites = 16,
            blacks = 15,
        ),
        color = PhotoFilter.Color(temp = 19, tint = 27, vibrance = 39),
        effects = PhotoFilter.Effects(grain = 10, vignette = -14),
        credit = "@_peppermint.b · 색감클래스 03 야경사진 (Canon Korea)",
    )

    /** Picker order: original first, then the six styles as `presets.json` lists them. */
    val ALL = listOf(
        ORIGINAL,
        CLEAN_SOCIAL,
        CANDID_FEED,
        BRIGHT_REVIEW,
        SOFT_FILM,
        CASUAL_PORTRAIT,
        NIGHT_STREET,
    )

    /** Looks up by `presets.json` id, falling back to [ORIGINAL] for an unknown id. */
    fun byId(id: String?): PhotoFilter = ALL.firstOrNull { it.id == id } ?: ORIGINAL
}
