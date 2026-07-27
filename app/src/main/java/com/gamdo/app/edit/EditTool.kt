package com.gamdo.app.edit

/**
 * One user-facing adjustment: its label, its range, and how to read and write it
 * on an [FilterEngine.Adjustments].
 *
 * ## Why the accessors live here
 *
 * The alternative is a `when (tool)` in the screen for reading and a second one
 * for writing, which is the shape that lets a control read 대비 and write 채도 —
 * a bug that looks like a broken slider and is invisible in review because both
 * branches are individually correct. Pairing them on the enum makes the screen a
 * loop over [entries] with no per-tool code at all, so a new adjustment is one
 * entry here and nothing anywhere else.
 *
 * Order follows **2f** of `감도 화면 디자인.dc.html` for the six controls it shows
 * (밝기 · 따뜻함 · 대비 · 채도 · 하이라이트 · 그림자), then the seven the design does
 * not name. Labels come from the design too where it names one — it says 밝기 and
 * 하이라이트 where Lightroom would say 노출 and 밝은 영역.
 */
enum class EditTool(
    val label: String,
    val range: IntRange,
    val get: (FilterEngine.Adjustments) -> Int,
    val set: (FilterEngine.Adjustments, Int) -> FilterEngine.Adjustments,
) {
    EXPOSURE(
        "밝기", -100..100,
        { it.exposure }, { a, v -> a.copy(exposure = v) },
    ),
    WARMTH(
        "따뜻함", -100..100,
        { it.warmth }, { a, v -> a.copy(warmth = v) },
    ),
    CONTRAST(
        "대비", -100..100,
        { it.contrast }, { a, v -> a.copy(contrast = v) },
    ),
    SATURATION(
        "채도", -100..100,
        { it.saturation }, { a, v -> a.copy(saturation = v) },
    ),
    HIGHLIGHTS(
        "하이라이트", -100..100,
        { it.highlights }, { a, v -> a.copy(highlights = v) },
    ),
    SHADOWS(
        "그림자", -100..100,
        { it.shadows }, { a, v -> a.copy(shadows = v) },
    ),
    WHITES(
        "흰색 계열", -100..100,
        { it.whites }, { a, v -> a.copy(whites = v) },
    ),
    BLACKS(
        "검정 계열", -100..100,
        { it.blacks }, { a, v -> a.copy(blacks = v) },
    ),
    VIBRANCE(
        "생동감", -100..100,
        { it.vibrance }, { a, v -> a.copy(vibrance = v) },
    ),
    TINT(
        "색조", -100..100,
        { it.tint }, { a, v -> a.copy(tint = v) },
    ),
    FADE(
        "페이드", 0..100,
        { it.fade }, { a, v -> a.copy(fade = v) },
    ),
    GRAIN(
        "입자", 0..100,
        { it.grain }, { a, v -> a.copy(grain = v) },
    ),
    VIGNETTE(
        "비네팅", -100..100,
        { it.vignette }, { a, v -> a.copy(vignette = v) },
    ),
    ;

    /**
     * True when the user has moved this control away from where the filter put it.
     *
     * Relative to [baseline], not to zero: after picking `soft_film` almost every
     * slider is non-zero, so marking "non-zero" would light up the whole strip and
     * say nothing. What a user needs to find again is what *they* changed.
     */
    fun isEdited(
        adjustments: FilterEngine.Adjustments,
        baseline: FilterEngine.Adjustments,
    ): Boolean = get(adjustments) != get(baseline)

    /** Where the value sits in its range, 0..1, for drawing the track fill. */
    fun fraction(value: Int): Float {
        val span = (range.last - range.first).toFloat()
        return if (span <= 0f) 0f else (value - range.first) / span
    }

    companion object {
        /** Serialises the whole set for `capture_edit_stack` (§4-1 비파괴 기록). */
        fun toJson(adjustments: FilterEngine.Adjustments): String =
            entries.joinToString(",", "{", "}") { tool ->
                "\"${tool.name.lowercase()}\":${tool.get(adjustments)}"
            }
    }
}
