package com.gamdo.app.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The filters this replaced were, numerically, the identity — `warmth = 0.08`
 * against a `22f` constant is 1.8 levels out of 255, and all six presets sat in
 * that range. Nothing failed; there was simply nothing to see. Six styles, one
 * picture.
 *
 * So the load-bearing tests here are not about correctness of any single slider.
 * They are [every_preset_visibly_changes_the_image] and
 * [every_preset_differs_from_every_other], which fail the moment a look becomes
 * too weak to notice or two looks converge. Everything else in this file exists to
 * explain *why* one of those two failed when it does.
 */
class FilterEngineTest {

    /** Perceptible difference, in mean absolute level out of 255. Two levels is
     *  roughly where a flat-field change stops being visible; a filter should be
     *  far past it. */
    private val visibleThreshold = 6.0

    // ---------------------------------------------------------------- fixtures

    /**
     * A test frame with the things filters act on: a full luminance ramp, a warm
     * midtone (skin-ish), a saturated blue, foliage green, and a near-blown
     * highlight.
     */
    private fun testImage(width: Int = 64, height: Int = 64): IntArray {
        val px = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                px[i] = when (y * 5 / height) {
                    0 -> gray(x * 255 / (width - 1))
                    1 -> rgb(226, 178, 149)          // skin
                    2 -> rgb(40, 90, 190)            // sky / denim
                    3 -> rgb(72, 122, 58)            // foliage
                    else -> rgb(248, 246, 240)       // near-blown highlight
                }
            }
        }
        return px
    }

    private fun gray(v: Int) = rgb(v, v, v)
    private fun rgb(r: Int, g: Int, b: Int) = (0xff shl 24) or (r shl 16) or (g shl 8) or b

    private fun meanAbsDiff(a: IntArray, b: IntArray): Double {
        var sum = 0L
        for (i in a.indices) {
            sum += abs(((a[i] shr 16) and 0xff) - ((b[i] shr 16) and 0xff)).toLong()
            sum += abs(((a[i] shr 8) and 0xff) - ((b[i] shr 8) and 0xff)).toLong()
            sum += abs((a[i] and 0xff) - (b[i] and 0xff)).toLong()
        }
        return sum.toDouble() / (a.size * 3)
    }

    /**
     * Renders a filter the way the screen does: seed the sliders from it, then
     * render those. A bare `apply(filter)` would render the *unedited* sliders and
     * so test nothing about the filter at all now that values are absolute.
     */
    private fun render(filter: PhotoFilter, source: IntArray = testImage()): IntArray {
        val out = source.copyOf()
        FilterEngine.apply(out, 64, 64, filter, seed(filter, source))
        return out
    }

    private fun seed(filter: PhotoFilter, source: IntArray = testImage()) =
        FilterEngine.seedFrom(filter, FilterEngine.measure(source))

    // ------------------------------------------------------- the two that matter

    @Test
    fun every_preset_visibly_changes_the_image() {
        val source = testImage()
        for (filter in PhotoFilters.ALL) {
            if (filter.id == "original") continue
            val diff = meanAbsDiff(source, render(filter, source))
            assertTrue(
                "${filter.label} moves the image by only $diff levels — that is the " +
                    "invisible-filter bug this suite exists to catch",
                diff > visibleThreshold,
            )
        }
    }

    @Test
    fun every_preset_differs_from_every_other() {
        val rendered = PhotoFilters.ALL
            .filter { it.id != "original" }
            .associateWith { render(it) }
        val entries = rendered.entries.toList()
        for (i in entries.indices) {
            for (j in i + 1 until entries.size) {
                val diff = meanAbsDiff(entries[i].value, entries[j].value)
                assertTrue(
                    "${entries[i].key.label} and ${entries[j].key.label} differ by only " +
                        "$diff levels — the style picker would be showing the same photo twice",
                    diff > visibleThreshold / 2,
                )
            }
        }
    }

    @Test
    fun original_is_the_identity() {
        val source = testImage()
        assertEquals(0.0, meanAbsDiff(source, render(PhotoFilters.ORIGINAL, source)), 0.0)
    }

    // ------------------------------------------------------------- tone curve

    @Test
    fun tone_curve_is_monotonic_for_every_preset() {
        for (filter in PhotoFilters.ALL) {
            val curve = FilterEngine.toneCurve(filter.tone)
            for (i in 1 until curve.size) {
                assertTrue(
                    "${filter.label}: tone curve decreases at sample $i " +
                        "(${curve[i - 1]} -> ${curve[i]}); a non-monotonic curve posterises",
                    curve[i] >= curve[i - 1] - 1e-4f,
                )
            }
        }
    }

    @Test
    fun tone_curve_output_stays_in_range() {
        for (filter in PhotoFilters.ALL) {
            for (v in FilterEngine.toneCurve(filter.tone)) {
                assertTrue("${filter.label}: tone curve produced $v", v in 0f..1f)
            }
        }
    }

    @Test
    fun highlight_recovery_compresses_the_top_toward_the_floor_not_to_black() {
        val recovered = FilterEngine.toneAt(1.0f, PhotoFilter.Tone(highlights = -100))
        // The naive form (y += h * w * y) sends this to 0 and turns a blown sky
        // into a black one. It must land near the upper midtones instead.
        assertEquals("highlights -100 should land white on the floor", 0.75f, recovered, 0.01f)
        // Over-range input (exposure pushed it past 1.0) compresses rather than
        // clipping, and stays above the 1.0 case because the curve is monotone.
        val blown = FilterEngine.toneAt(1.6f, PhotoFilter.Tone(highlights = -100))
        assertTrue("over-range highlight landed at $blown", blown in 0.80f..0.90f)
        assertTrue("recovery must stay monotone across 1.0", blown > recovered)
    }

    @Test
    fun shadow_lift_raises_black_without_reaching_mid_grey() {
        val lifted = FilterEngine.toneAt(0f, PhotoFilter.Tone(shadows = 100))
        assertTrue("shadows +100 lifted black to $lifted", lifted in 0.25f..0.40f)
    }

    @Test
    fun whites_and_blacks_move_the_endpoints() {
        val liftedBlack = FilterEngine.toneAt(0f, PhotoFilter.Tone(blacks = 48))
        assertTrue("blacks +48 lifted black to $liftedBlack", liftedBlack > 0.08f)

        val crushedBlack = FilterEngine.toneAt(0.05f, PhotoFilter.Tone(blacks = -43))
        assertEquals("blacks -43 should crush 0.05 to zero", 0f, crushedBlack, 1e-4f)

        val pushedWhite = FilterEngine.toneAt(0.9f, PhotoFilter.Tone(whites = 40))
        assertEquals("whites +40 should clip 0.9 to white", 1f, pushedWhite, 1e-3f)
    }

    @Test
    fun contrast_fixes_black_mid_and_white() {
        for (amount in listOf(-0.6f, -0.22f, 0.18f, 0.5f)) {
            assertEquals(0f, FilterEngine.contrastAt(0f, amount), 1e-4f)
            assertEquals(0.5f, FilterEngine.contrastAt(0.5f, amount), 1e-3f)
            assertEquals(1f, FilterEngine.contrastAt(1f, amount), 1e-4f)
        }
    }

    @Test
    fun negative_contrast_is_the_analytic_inverse_of_positive() {
        // Not "looks similar": +c then -c has to come back, or the slider drifts
        // every time the user changes their mind.
        for (x in listOf(0.1f, 0.25f, 0.4f, 0.6f, 0.75f, 0.9f)) {
            val round = FilterEngine.contrastAt(FilterEngine.contrastAt(x, 1f), -1f)
            assertEquals("round trip at $x", x, round, 1e-3f)
        }
    }

    // --------------------------------------------------------------- exposure

    @Test
    fun published_exposure_is_capped_by_measured_headroom() {
        // bright_review publishes +2.18 EV because its author was rescuing a
        // backlit subject. On a frame that is already bright it must not apply.
        val bright = FilterEngine.Measure(meanLuma = 0.72f, p99 = 0.98f)
        val dark = FilterEngine.Measure(meanLuma = 0.12f, p99 = 0.22f)
        val published = PhotoFilters.BRIGHT_REVIEW.tone.exposureEv

        val onBright = FilterEngine.effectiveExposureEv(published, bright)
        val onDark = FilterEngine.effectiveExposureEv(published, dark)

        assertTrue("a bright frame still got $onBright EV", onBright < 0.5f)
        assertTrue("a dark frame only got $onDark EV of a published $published", onDark > 1.5f)
        assertTrue("cap must never exceed the published value", onDark <= published)
    }

    @Test
    fun negative_exposure_passes_through_uncapped() {
        val m = FilterEngine.Measure(meanLuma = 0.8f, p99 = 0.99f)
        assertEquals(-0.4f, FilterEngine.effectiveExposureEv(-0.4f, m), 1e-6f)
    }

    @Test
    fun a_blown_frame_is_not_brightened_further() {
        val blown = FilterEngine.Measure(meanLuma = 0.95f, p99 = 1.0f)
        assertTrue(FilterEngine.effectiveExposureEv(2.18f, blown) <= CLIP_TOLERANCE)
    }

    // --------------------------------------------------------- white balance

    @Test
    fun white_balance_preserves_luminance() {
        // Warming a photo must not also brighten it, or every warmth change has to
        // be paid back with an exposure change.
        for (temp in listOf(-100, -26, 0, 19, 26, 100)) {
            val g = FilterEngine.whiteBalanceGains(temp, 0)
            val luma = 0.2126f * g[0] + 0.7152f * g[1] + 0.0722f * g[2]
            assertEquals("temp $temp changed luminance", 1f, luma, 1e-4f)
        }
    }

    @Test
    fun positive_temp_is_warmer_and_negative_is_cooler() {
        val warm = FilterEngine.whiteBalanceGains(26, 0)
        assertTrue("temp +26 should gain red over blue", warm[0] > warm[2])
        val cool = FilterEngine.whiteBalanceGains(-26, 0)
        assertTrue("temp -26 should gain blue over red", cool[2] > cool[0])
    }

    @Test
    fun positive_tint_is_magenta() {
        val g = FilterEngine.whiteBalanceGains(0, 27)
        assertTrue("tint +27 should suppress green", g[1] < 1f)
    }

    // ----------------------------------------------------------- colour mixer

    @Test
    fun hue_table_is_identity_without_rows() {
        val t = FilterEngine.hueTable(emptyList())
        for (deg in 0 until 360) {
            assertEquals(1f, t[0][deg], 1e-6f)
            assertEquals(0f, t[1][deg], 1e-6f)
            assertEquals(1f, t[2][deg], 1e-6f)
        }
    }

    @Test
    fun hue_table_peaks_at_the_band_centre_and_falls_off_smoothly() {
        val t = FilterEngine.hueTable(
            listOf(PhotoFilter.HueAdjust(HueBand.YELLOW, saturation = 100)),
        )
        val sat = t[0]
        assertEquals("yellow centre should get the full row", 2f, sat[60], 1e-2f)
        assertTrue("red should be untouched by a yellow row", abs(sat[0] - 1f) < 1e-3f)
        assertTrue(
            "a yellow row must not reach skin, which sits near 25°",
            abs(sat[25] - 1f) < 1e-3f,
        )
        // No contour: neighbouring degrees must never jump. The bound is the
        // analytic maximum slope of a raised cosine at this band's half-width
        // (0.5·π/30 ≈ 0.052 per degree for a ±100 row), plus a hair.
        for (deg in 1 until 360) {
            assertTrue(
                "hue table jumps at $deg — that shows as a contour across a sky",
                abs(sat[deg] - sat[deg - 1]) < 0.06f,
            )
        }
    }

    // ------------------------------------------------------------------ grain

    @Test
    fun grain_is_deterministic_and_centred() {
        assertEquals(FilterEngine.grainAt(12345), FilterEngine.grainAt(12345), 0f)
        assertNotEquals(FilterEngine.grainAt(1), FilterEngine.grainAt(2))
        var sum = 0.0
        for (i in 0 until 20000) {
            val n = FilterEngine.grainAt(i)
            assertTrue("grain out of range: $n", n in -0.5f..0.5f)
            sum += n
        }
        assertEquals("grain should be zero-mean or it is a brightness change", 0.0, sum / 20000, 0.02)
    }

    // ----------------------------------------------------------- whole render

    @Test
    fun render_never_produces_out_of_range_or_transparent_pixels() {
        for (filter in PhotoFilters.ALL) {
            for (p in render(filter)) {
                assertEquals("${filter.label} changed alpha", 0xff, (p ushr 24) and 0xff)
            }
        }
    }

    @Test
    fun choosing_a_filter_seeds_every_slider_from_its_recipe() {
        // The point of the whole absolute model: what the sliders read is the
        // filter, so the panel can show it and the user can move it.
        val m = FilterEngine.measure(testImage())
        for (filter in PhotoFilters.ALL) {
            val seeded = FilterEngine.seedFrom(filter, m)
            assertEquals(filter.label, filter.tone.contrast, seeded.contrast)
            assertEquals(filter.label, filter.tone.highlights, seeded.highlights)
            assertEquals(filter.label, filter.tone.shadows, seeded.shadows)
            assertEquals(filter.label, filter.tone.whites, seeded.whites)
            assertEquals(filter.label, filter.tone.blacks, seeded.blacks)
            assertEquals(filter.label, filter.color.temp, seeded.warmth)
            assertEquals(filter.label, filter.color.tint, seeded.tint)
            assertEquals(filter.label, filter.color.vibrance, seeded.vibrance)
            assertEquals(filter.label, filter.color.saturation, seeded.saturation)
            assertEquals(filter.label, filter.effects.fade, seeded.fade)
            assertEquals(filter.label, filter.effects.grain, seeded.grain)
            assertEquals(filter.label, filter.effects.vignette, seeded.vignette)
        }
        assertEquals(
            "원본 must seed a blank panel",
            FilterEngine.Adjustments.NEUTRAL,
            FilterEngine.seedFrom(PhotoFilters.ORIGINAL, m),
        )
    }

    @Test
    fun seeded_exposure_is_the_capped_value_not_the_published_one() {
        // bright_review publishes +2.18 EV for a backlit rescue. The slider has to
        // read what this photo is actually getting, or it lies about the render.
        val bright = FilterEngine.Measure(meanLuma = 0.72f, p99 = 0.98f)
        val dark = FilterEngine.Measure(meanLuma = 0.12f, p99 = 0.22f)
        val onBright = FilterEngine.seedFrom(PhotoFilters.BRIGHT_REVIEW, bright).exposure
        val onDark = FilterEngine.seedFrom(PhotoFilters.BRIGHT_REVIEW, dark).exposure
        assertTrue("bright frame seeded $onBright", onBright < 25)
        assertTrue("dark frame seeded $onDark", onDark > 60)
        assertTrue("seed must stay inside the control", onDark <= 100)
    }

    // ------------------------------------------------------- manual adjustments

    private fun adjust(tool: EditTool, value: Int) =
        tool.set(FilterEngine.Adjustments.NEUTRAL, value)

    /**
     * The one that matters for the panel: fourteen controls, and every one of them
     * has to actually reach the pixels. Ten of them did not exist before — the
     * engine supported them and no caller could set them — so a control that is
     * wired to nothing is the exact regression to guard.
     */
    @Test
    fun every_tool_changes_the_image_in_both_of_its_directions() {
        val source = testImage()
        for (tool in EditTool.entries) {
            // Judged against ORIGINAL, whose seed is all zeros, so every control
            // is moved on its own from a known blank state.
            val base = PhotoFilters.ORIGINAL
            val settings = buildList {
                if (tool.range.last > 0) add(tool.range.last)
                if (tool.range.first < 0) add(tool.range.first)
            }
            assertTrue("${tool.label} has no usable range", settings.isNotEmpty())
            for (value in settings) {
                val out = source.copyOf()
                FilterEngine.apply(out, 64, 64, base, adjust(tool, value))
                val neutral = source.copyOf().also { FilterEngine.apply(it, 64, 64, base) }
                val diff = meanAbsDiff(neutral, out)
                assertTrue(
                    "${tool.label} at $value moved the image by only $diff levels — " +
                        "that control is not reaching the pixels",
                    diff > 1.0,
                )
            }
        }
    }

    @Test
    fun neutral_adjustments_leave_a_filter_exactly_as_it_was() {
        val source = testImage()
        for (filter in PhotoFilters.ALL) {
            val implicit = source.copyOf().also { FilterEngine.apply(it, 64, 64, filter) }
            val explicit = source.copyOf().also {
                FilterEngine.apply(it, 64, 64, filter, FilterEngine.Adjustments.NEUTRAL)
            }
            assertEquals("${filter.label}", 0.0, meanAbsDiff(implicit, explicit), 0.0)
        }
    }

    @Test
    fun tool_accessors_round_trip() {
        // A get/set pair that disagree is a slider that edits a different control
        // than the one it is labelled with, and both halves look correct alone.
        var a = FilterEngine.Adjustments.NEUTRAL
        for ((i, tool) in EditTool.entries.withIndex()) {
            val v = tool.range.first + (i * 7) % (tool.range.last - tool.range.first + 1)
            a = tool.set(a, v)
            assertEquals("${tool.label} did not read back what it wrote", v, tool.get(a))
        }
        // ...and writing one must not disturb another.
        for (tool in EditTool.entries) {
            val v = tool.range.first + (EditTool.entries.indexOf(tool) * 7) %
                (tool.range.last - tool.range.first + 1)
            assertEquals("${tool.label} was clobbered by a later write", v, tool.get(a))
        }
    }

    @Test
    fun neutral_reads_zero_everywhere() {
        val neutral = FilterEngine.Adjustments.NEUTRAL
        for (tool in EditTool.entries) {
            assertEquals(tool.label, 0, tool.get(neutral))
            assertTrue("${tool.label}'s zero is outside its own range", 0 in tool.range)
            assertTrue(tool.label, !tool.isEdited(neutral, neutral))
        }
    }

    @Test
    fun edited_is_measured_against_the_filter_not_against_zero() {
        // After picking soft_film almost every slider is non-zero. Marking those
        // "edited" would light the whole strip and say nothing about what the user
        // themselves changed.
        val baseline = seed(PhotoFilters.SOFT_FILM)
        for (tool in EditTool.entries) {
            assertTrue(
                "${tool.label} is marked edited immediately after choosing a filter",
                !tool.isEdited(baseline, baseline),
            )
        }
        val moved = EditTool.CONTRAST.set(baseline, EditTool.CONTRAST.get(baseline) + 20)
        assertTrue(EditTool.CONTRAST.isEdited(moved, baseline))
        assertTrue(!EditTool.SHADOWS.isEdited(moved, baseline))
    }

    @Test
    fun the_exposure_slider_is_not_capped_by_headroom() {
        // The cap applies when *seeding* a recipe written for someone else's photo.
        // A slider the user is holding is not that: a bright frame must still
        // brighten when they ask it to.
        val bright = IntArray(64 * 64) { rgb(230, 230, 230) }
        val out = bright.copyOf()
        FilterEngine.apply(out, 64, 64, PhotoFilters.ORIGINAL, adjust(EditTool.EXPOSURE, 100))
        val before = bright.sumOf { (it shr 8) and 0xff }
        val after = out.sumOf { (it shr 8) and 0xff }
        assertTrue("manual exposure did nothing on a bright frame", after > before)
    }

    @Test
    fun adjustments_serialise_every_control() {
        val json = EditTool.toJson(
            adjust(EditTool.CONTRAST, 32).let { EditTool.VIGNETTE.set(it, -18) },
        )
        for (tool in EditTool.entries) {
            assertTrue(
                "${tool.label} is missing from the saved record",
                json.contains("\"${tool.name.lowercase()}\""),
            )
        }
        assertTrue(json.contains("\"contrast\":32"))
        assertTrue(json.contains("\"vignette\":-18"))
    }

    @Test
    fun manual_sliders_move_the_result_in_the_direction_they_name() {
        val source = testImage()
        fun meanChannel(px: IntArray, shift: Int): Double =
            px.sumOf { ((it shr shift) and 0xff).toDouble() } / px.size

        val base = source.copyOf().also {
            FilterEngine.apply(it, 64, 64, PhotoFilters.ORIGINAL)
        }
        val brighter = source.copyOf().also {
            FilterEngine.apply(it, 64, 64, PhotoFilters.ORIGINAL, adjust(EditTool.EXPOSURE, 30))
        }
        assertTrue("노출 slider did not brighten", meanChannel(brighter, 8) > meanChannel(base, 8))

        val warmer = source.copyOf().also {
            FilterEngine.apply(it, 64, 64, PhotoFilters.ORIGINAL, adjust(EditTool.WARMTH, 40))
        }
        assertTrue(
            "따뜻함 slider did not shift red above blue",
            meanChannel(warmer, 16) - meanChannel(warmer, 0) >
                meanChannel(base, 16) - meanChannel(base, 0),
        )
    }

    @Test
    fun every_preset_carries_its_source() {
        for (filter in PhotoFilters.ALL) {
            if (filter.id == "original") continue
            assertTrue(
                "${filter.label} has no credit — these are other people's recipes",
                filter.credit.isNotBlank(),
            )
        }
    }

    private companion object {
        const val CLIP_TOLERANCE = 0.36f
    }
}
