package com.gamdo.app.camera.gl

import com.gamdo.app.edit.FilterEngine
import com.gamdo.app.edit.PhotoFilter
import com.gamdo.app.edit.PhotoFilters
import com.gamdo.app.ui.camera.PreviewColorMatrix
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O-14's central claim, measured: **the preview shader's colour is the editor's
 * colour.**
 *
 * The shader cannot run here, so what is held against [FilterEngine] is
 * [PreviewFilterModel] — the Kotlin transliteration of the same `.frag`, reading
 * the same LUT bytes through the same decode. See that class's KDoc for exactly how
 * much this proves and what is left for the device.
 *
 * Errors are reported in **8-bit levels**, because that is the unit the
 * disagreement is visible in. "Within one level" means the two answers differ by at
 * most a single value out of 255 on any channel of any colour tested — below the
 * point where a display, let alone an eye, can separate them.
 */
class PreviewFilterModelTest {

    /**
     * A mid-bright frame. Exposure is the one slider seeded from a *measurement*
     * ([FilterEngine.seedFrom]), so every parity check has to fix one or it is
     * comparing two different recipes.
     */
    private val measure = FilterEngine.Measure(meanLuma = 0.45f, p99 = 0.82f)

    /**
     * The recipe the preview actually ships (O-15 (2): no exposure), with the
     * positional stages off so colour can be compared on its own.
     */
    private fun spec(filter: PhotoFilter): PreviewFilterSpec = PreviewFilterSpec.of(
        filter,
        PreviewFilterSpec.previewAdjustments(filter).copy(grain = 0, vignette = 0),
    )

    /** What [FilterEngine] does to [pixels], laid out `width` × `height`. */
    private fun engine(filter: PhotoFilter, spec: PreviewFilterSpec, pixels: IntArray, width: Int, height: Int): IntArray {
        val out = pixels.copyOf()
        FilterEngine.apply(out, width, height, filter, spec.adjustments)
        return out
    }

    private fun model(spec: PreviewFilterSpec, pixels: IntArray, width: Int, height: Int): IntArray {
        val m = PreviewFilterModel(spec)
        return IntArray(pixels.size) { i ->
            m.apply(pixels[i], i % width, i / width, width, height)
        }
    }

    /** Worst and RMS disagreement over every channel of every pixel, in levels. */
    private fun error(expected: IntArray, actual: IntArray): Pair<Float, Float> {
        var worst = 0f
        var squares = 0.0
        var count = 0
        for (i in expected.indices) {
            for (shift in intArrayOf(16, 8, 0)) {
                val d = abs(((expected[i] shr shift) and 0xff) - ((actual[i] shr shift) and 0xff)).toFloat()
                worst = max(worst, d)
                squares += d.toDouble() * d
                count++
            }
        }
        return worst to sqrt(squares / max(count, 1)).toFloat()
    }

    /** 16³ = 4096 colours spread over the whole RGB cube. */
    private fun lattice(steps: Int = 16): IntArray {
        val values = IntArray(steps) { (it * 255f / (steps - 1)).roundToInt() }
        val out = IntArray(steps * steps * steps)
        var i = 0
        for (r in values) for (g in values) for (b in values) {
            out[i++] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
        }
        return out
    }

    private fun greyRamp(): IntArray =
        IntArray(256) { v -> (0xff shl 24) or (v shl 16) or (v shl 8) or v }

    @Test
    fun `every shipped preset matches the editor to within three levels`() {
        // The whole reason O-14 chose the GL path over a colour matrix. Run over
        // the full cube, so a preset whose colour mixer only touches one hue band
        // is still tested where it acts.
        //
        // The bound is 3 and not 1, and the 3 is one specific, understood thing —
        // see `a colour landing on a hue bin boundary can differ by one bin`. Five
        // of the seven presets are within 1 across the whole cube; RMS is under
        // 0.07 levels for all of them, i.e. the disagreement is a handful of
        // isolated colours rather than a tint.
        val cube = lattice()
        for (filter in PhotoFilters.ALL) {
            val spec = spec(filter)
            val (worst, rms) = error(
                engine(filter, spec, cube, cube.size, 1),
                model(spec, cube, cube.size, 1),
            )
            assertTrue(
                "${filter.id}: worst $worst levels, rms $rms — expected <= 3",
                worst <= 3f,
            )
            assertTrue("${filter.id}: rms $rms is above quantisation noise", rms < 0.1f)
        }
    }

    @Test
    fun `a colour landing on a hue bin boundary can differ by one bin`() {
        // The single source of every disagreement above 1 level, pinned so it is a
        // known quantity rather than a mystery in a report.
        //
        // FilterEngine's colour mixer is a **step function of hue quantised to
        // integer degrees** (`hueTable` has 360 entries and `deg.toInt()` picks
        // one). RGB(153,119,204) computes to hue 265.00003° in the engine and
        // 264.99915° through the 16-bit LUT — a relative difference of 3e-6, on
        // opposite sides of a boundary. `bright_review` sets 파란색 명도 -71, so
        // neighbouring bins really are ~1.4% apart in luminance, and one bin of
        // slip is 3 levels on a bright pixel.
        //
        // This is not fixable by more precision: any two implementations that do
        // not produce bit-identical channel values will straddle some boundary
        // somewhere. It is bounded by how fast the raised-cosine falloff can move
        // between adjacent degrees, which is what makes 3 a ceiling and not a
        // symptom.
        val filter = PhotoFilters.BRIGHT_REVIEW
        val spec = spec(filter)
        val pixel = intArrayOf((0xff shl 24) or (153 shl 16) or (119 shl 8) or 204)
        val (worst, _) = error(
            engine(filter, spec, pixel, 1, 1),
            model(spec, pixel, 1, 1),
        )
        assertTrue("the known boundary pixel now differs by $worst levels", worst <= 3f)

        // One degree either side of the boundary, the two agree exactly — which is
        // what makes this a boundary effect and not a broken colour mixer.
        val offBoundary = intArrayOf((0xff shl 24) or (150 shl 16) or (119 shl 8) or 204)
        val (clean, _) = error(
            engine(filter, spec, offBoundary, 1, 1),
            model(spec, offBoundary, 1, 1),
        )
        assertTrue("away from the boundary the two should agree, got $clean", clean <= 1f)
    }

    @Test
    fun `parity holds across the exposure range too`() {
        // The preview never sets exposure (O-15 (2)), but the LUT builder is
        // general and the channel tables are where exposure lives. Testing only at
        // zero would leave the widest part of the table's range — and the reason
        // CHANNEL_SCALE exists at all — unexercised.
        val cube = lattice()
        for (exposure in intArrayOf(-100, -40, 40, 100)) {
            val filter = PhotoFilters.CANDID_FEED
            val adjustments = PreviewFilterSpec.previewAdjustments(filter)
                .copy(exposure = exposure, grain = 0, vignette = 0)
            val spec = PreviewFilterSpec.of(filter, adjustments)
            val (worst, _) = error(
                engine(filter, spec, cube, cube.size, 1),
                model(spec, cube, cube.size, 1),
            )
            assertTrue("exposure $exposure: off by $worst levels", worst <= 3f)
        }
    }

    @Test
    fun `the grey ramp matches, which is where the tone curve lives`() {
        // Neutrals are most of a real frame and are what the eye reads as "the
        // photo got brighter / flatter". `clean_social` lifts pure black by 89
        // levels here — the exact move a matrix could not make.
        val ramp = greyRamp()
        for (filter in PhotoFilters.ALL) {
            val spec = spec(filter)
            val (worst, _) = error(
                engine(filter, spec, ramp, ramp.size, 1),
                model(spec, ramp, ramp.size, 1),
            )
            assertTrue("${filter.id}: grey ramp off by $worst levels", worst <= 1f)
        }
    }

    @Test
    fun `vignetting lands in the same place as the file`() {
        // 비네팅 is positional, and the only preset that carries it is 밤거리
        // (-14). Reproduced exactly rather than dropped: it is a function of
        // normalised distance from centre, which survives a resize as long as the
        // shader measures from the *crop* the file will have. See
        // PreviewFilterModel's `x, y` contract.
        val width = 24
        val height = 30
        val pixels = IntArray(width * height) { i ->
            (0xff shl 24) or (0x80 shl 16) or (0x90 shl 8) or 0xa0 + (i % 3)
        }
        val filter = PhotoFilters.NIGHT_STREET
        val seeded = FilterEngine.seedFrom(filter, measure).copy(grain = 0)
        val spec = PreviewFilterSpec.of(filter, seeded)
        assertTrue("this test is vacuous without a vignette", spec.vignette != 0f)
        val (worst, _) = error(
            engine(filter, spec, pixels, width, height),
            model(spec, pixels, width, height),
        )
        assertTrue("vignette off by $worst levels", worst <= 1f)
    }

    @Test
    fun `fade is reproduced exactly and is not folded into the tone curve`() {
        // 페이드 is 소프트 필름's matte lift. It is affine, so there is no excuse
        // for it to be approximate.
        val filter = PhotoFilters.SOFT_FILM
        val spec = spec(filter)
        assertTrue("soft_film should carry fade", spec.fade > 0f)
        val ramp = greyRamp()
        val (worst, _) = error(
            engine(filter, spec, ramp, ramp.size, 1),
            model(spec, ramp, ramp.size, 1),
        )
        assertTrue("faded ramp off by $worst levels", worst <= 1f)
    }

    @Test
    fun `grain keeps the engine's amplitude and mean without keeping its pattern`() {
        // Grain cannot be pixel-reproduced across a resize — the engine hashes the
        // pixel's buffer index and the preview is not the file's size. What must
        // survive is the statistics, because that is all grain is.
        var sum = 0.0
        var worst = 0f
        var count = 0
        for (y in 0 until 64) {
            for (x in 0 until 64) {
                val n = grainAt(x.toFloat(), y.toFloat())
                assertTrue("grain $n out of range at ($x, $y)", n >= -0.5f && n < 0.5f)
                sum += n.toDouble()
                worst = max(worst, abs(n))
                count++
            }
        }
        val mean = sum / count
        assertTrue("grain mean $mean is not centred", abs(mean) < 0.02)
        assertTrue("grain never reaches its amplitude (worst $worst)", worst > 0.45f)
        // Deterministic: the same pixel must not shimmer between frames.
        assertEquals(grainAt(17f, 43f), grainAt(17f, 43f), 0f)
        assertTrue(grainAt(17f, 43f) != grainAt(18f, 43f))
    }

    @Test
    fun `the fitted colour matrix is the thing this replaces`() {
        // Keeps O-14's premise honest and executable rather than a claim in a
        // decision table. If someone ever makes the matrix good enough, this test
        // fails and the GL pipeline plus its second EGL context can be deleted.
        //
        // ## Read the seed before quoting these numbers
        //
        // The matrix error depends on the recipe it was fitted to, and 노출 moves it.
        // This test fits against **the recipe the shader actually ships** —
        // `previewAdjustments`, exposure omitted per O-15 (2). `PreviewColorMatrixTest`
        // fits against `seedFrom(filter, Measure(0.45, 0.90))`, which carries
        // exposure at slider 25, and correctly reports different figures:
        //
        // | seed                          | 노출 | cube | grey |
        // |---|---|---|---|
        // | this test (exposure omitted)  |  0  | 153  |  90  |
        // | PreviewColorMatrixTest        | 25  | 154  |  89  |
        //
        // Both are real; neither is the other's. Quoting a number without its seed
        // is how the two tests come to look like they contradict each other.
        val filter = PhotoFilters.CLEAN_SOCIAL
        val spec = spec(filter)
        val cube = lattice()
        val ramp = greyRamp()
        val matrix = PreviewColorMatrix.fit(filter, spec.adjustments)

        fun matrixError(pixels: IntArray): Float = error(
            engine(filter, spec, pixels, pixels.size, 1),
            IntArray(pixels.size) { PreviewColorMatrix.applyTo(matrix, pixels[it]) },
        ).first

        // Characterisation, pinned exactly: every input is deterministic, so a
        // change here means a preset recipe or the fit moved and the prose that
        // cites these numbers needs revisiting with it.
        assertEquals("matrix, RGB cube", 153f, matrixError(cube), 0f)
        assertEquals("matrix, grey ramp", 90f, matrixError(ramp), 0f)

        val (modelCube, _) = error(
            engine(filter, spec, cube, cube.size, 1),
            model(spec, cube, cube.size, 1),
        )
        val (modelGrey, _) = error(
            engine(filter, spec, ramp, ramp.size, 1),
            model(spec, ramp, ramp.size, 1),
        )
        assertTrue("the shader model should be exact on the cube, got $modelCube", modelCube <= 3f)
        assertTrue("the shader model should be exact on the ramp, got $modelGrey", modelGrey <= 1f)
    }

    @Test
    fun `the shadow lift that sank the matrix is reproduced, and pure black is not`() {
        // O-14 names this number: clean_social must lift pure black by 89 levels
        // and "a straight line cannot do that while leaving the top half alone".
        // That is a statement about the **tone curve**, and it is exactly right:
        val tone = PhotoFilter.Tone(highlights = -100, shadows = 100, whites = 40)
        val liftedCurve = FilterEngine.toneAt(0f, tone) * 255f
        assertTrue("the curve lifts 0 to $liftedCurve, expected ~90", liftedCurve > 85f)

        // ...but it is *not* a statement about what `apply` does to a pure black
        // pixel, and the difference is worth pinning so nobody 'fixes' the shader
        // to match the curve. The engine applies tone as the ratio `curve(L)/L`,
        // which it cannot evaluate at L = 0, so it leaves (0,0,0) alone — and the
        // shader must leave it alone too, or preview and file diverge on every
        // clipped shadow in the frame.
        val filter = PhotoFilters.CLEAN_SOCIAL
        val spec = spec(filter)
        val black = intArrayOf(0xff shl 24)
        assertEquals(0, model(spec, black, 1, 1)[0] and 0xff)
        assertEquals(engine(filter, spec, black, 1, 1)[0] and 0xff, model(spec, black, 1, 1)[0] and 0xff)

        // One level above black is where the lift becomes enormous — and where a
        // fitted line is hopeless, because it has to be near-zero at 0 and near-100
        // four levels later.
        val nearBlack = intArrayOf((0xff shl 24) or (4 shl 16) or (4 shl 8) or 4)
        val lifted = model(spec, nearBlack, 1, 1)[0] and 0xff
        assertTrue("RGB(4,4,4) came out at $lifted, expected a large lift", lifted > 60)
        assertEquals(
            "the model must agree with the engine on the hardest pixel in the frame",
            engine(filter, spec, nearBlack, 1, 1)[0] and 0xff,
            lifted,
        )
    }

    @Test
    fun `the preview drops exposure and nothing else`() {
        // O-15 (2). Guard against a future reader "fixing" the brightness gap by
        // putting exposure back — see PreviewFilterSpec.previewAdjustments for why
        // there is no honest value to put there.
        for (filter in PhotoFilters.ALL) {
            val preview = PreviewFilterSpec.previewAdjustments(filter)
            val editor = FilterEngine.seedFrom(filter, FilterEngine.Measure(0.45f, 0.82f))
            assertEquals("${filter.id} must preview at zero exposure", 0, preview.exposure)
            assertEquals(
                "${filter.id}: only exposure may differ from the editor's recipe",
                editor.copy(exposure = 0),
                preview,
            )
        }
    }

    @Test
    fun `the measure cannot influence the preview`() {
        // previewAdjustments has to hand FilterEngine.seedFrom *some* Measure. This
        // pins that the choice is inert, so nobody has to wonder whether the
        // sentinel was tuned.
        for (filter in PhotoFilters.ALL) {
            assertEquals(
                PreviewFilterSpec.of(filter),
                PreviewFilterSpec.of(
                    filter,
                    FilterEngine.seedFrom(filter, FilterEngine.Measure(0.01f, 0.02f)).copy(exposure = 0),
                ),
            )
        }
    }

    @Test
    fun `the preview is never brighter than the file`() {
        // The property that makes O-15 (2)'s omission acceptable: the disagreement
        // has one direction. Every shipped preset publishes a non-negative
        // exposure, so dropping the term can only darken. A preset that ever
        // published a negative exposure would break this and would need its own
        // decision — hence the assertion rather than a comment.
        // Both sides run with 입자 and 비네팅 off. Grain is ±10 levels of zero-mean
        // noise on soft_film, which swamps the exposure difference being measured
        // and is not a claim about brightness — the first version of this test
        // compared a grain-on preview against a grain-off file and failed on
        // soft_film by exactly one grain sample.
        val ramp = greyRamp()
        for (filter in PhotoFilters.ALL) {
            val previewSpec = spec(filter)
            val fileAdjustments = FilterEngine.seedFrom(filter, measure).copy(grain = 0, vignette = 0)
            val preview = engine(filter, previewSpec, ramp, ramp.size, 1)
            val file = ramp.copyOf().also { FilterEngine.apply(it, it.size, 1, filter, fileAdjustments) }
            for (i in ramp.indices) {
                for (shift in intArrayOf(16, 8, 0)) {
                    val p = (preview[i] shr shift) and 0xff
                    val f = (file[i] shr shift) and 0xff
                    assertTrue(
                        "${filter.id}: preview $p is brighter than file $f at ramp $i",
                        p <= f,
                    )
                }
            }
        }
    }

    @Test
    fun `the accepted cost is real and is largest on bright_review`() {
        // Names the size of what the owner accepted, so it is a known quantity
        // rather than a surprise on device. bright_review's whole identity is the
        // lift; 원본 and the restrained presets barely move.
        val ramp = greyRamp()
        fun gap(filter: PhotoFilter): Int {
            val preview = engine(filter, spec(filter), ramp, ramp.size, 1)
            val file = ramp.copyOf().also {
                FilterEngine.apply(
                    it, it.size, 1, filter,
                    FilterEngine.seedFrom(filter, measure).copy(grain = 0, vignette = 0),
                )
            }
            return ramp.indices.maxOf { ((file[it] shr 16) and 0xff) - ((preview[it] shr 16) and 0xff) }
        }
        assertEquals("원본 has no exposure to drop", 0, gap(PhotoFilters.ORIGINAL))
        assertTrue(
            "bright_review should be the most affected, not the least",
            gap(PhotoFilters.BRIGHT_REVIEW) > gap(PhotoFilters.SOFT_FILM),
        )
    }
}

/**
 * The LUT's storage contract: the encodings must not clip anything a shipped preset
 * can produce, and the round trip must be lossless enough to disappear at 8 bits.
 */
class PreviewFilterLutTest {

    private val measure = FilterEngine.Measure(meanLuma = 0.45f, p99 = 0.82f)

    @Test
    fun `sixteen bit round trip costs less than a hundredth of a level`() {
        val lut = PreviewFilterLut.build(
            PhotoFilters.CLEAN_SOCIAL,
            FilterEngine.seedFrom(PhotoFilters.CLEAN_SOCIAL, measure),
        )
        val curve = FilterEngine.toneCurve(
            PhotoFilter.Tone(
                contrast = 0, highlights = -100, shadows = 100, whites = 40, blacks = 0,
            ),
        )
        var worst = 0f
        for (i in 0 until PreviewFilterLut.TONE_SAMPLES) {
            val decoded = PreviewFilterLut.read16(lut, i, PreviewFilterLut.ROW_TONE)
            worst = max(worst, abs(decoded - curve[i]) * 255f)
        }
        assertTrue("tone curve round trip is off by $worst levels", worst < 0.01f)
    }

    @Test
    fun `the channel scale clears the highest value the control can produce`() {
        // If this ever clips, highlights silently stop recovering: the engine
        // leaves the channel tables unclamped precisely so the tone curve has
        // over-range to pull back down.
        var ceiling = 0f
        for (temp in -100..100 step 10) {
            for (tint in -100..100 step 10) {
                val gains = FilterEngine.whiteBalanceGains(temp, tint)
                for (gain in gains) {
                    // The exposure control's own limit, which is the worst case.
                    val scaled = gain * Math.pow(2.0, FilterEngine.MANUAL_EXPOSURE_EV.toDouble()).toFloat()
                    ceiling = max(ceiling, PreviewFilterLut.channelValue(255, scaled))
                }
            }
        }
        assertTrue(
            "channel ceiling $ceiling does not fit in ${PreviewFilterLut.CHANNEL_SCALE}",
            ceiling < PreviewFilterLut.CHANNEL_SCALE,
        )
        // ...and is not wastefully large either: every bit of unused range is
        // precision thrown away in a 16-bit fixed-point store.
        assertTrue(
            "scale ${PreviewFilterLut.CHANNEL_SCALE} is oversized for a ceiling of $ceiling",
            PreviewFilterLut.CHANNEL_SCALE < ceiling * 1.25f,
        )
    }

    @Test
    fun `the hue scale clears every shipped colour mixer`() {
        var ceiling = 0f
        for (filter in PhotoFilters.ALL) {
            val table = FilterEngine.hueTable(filter.hsl)
            for (deg in 0 until 360) {
                ceiling = max(ceiling, max(table[0][deg], table[2][deg]))
                assertTrue(
                    "${filter.id}: hue shift ${table[1][deg]}° is outside the stored range",
                    abs(table[1][deg]) < PreviewFilterLut.SHIFT_BIAS,
                )
            }
        }
        assertTrue(
            "hue ceiling $ceiling does not fit in ${PreviewFilterLut.HUE_SCALE}",
            ceiling < PreviewFilterLut.HUE_SCALE,
        )
    }

    @Test
    fun `a spec is equal when the recipe is equal, whatever array it built`() {
        // The GL thread re-uploads on inequality. Comparing the byte arrays by
        // identity would re-upload every frame; comparing them by content would
        // walk 12KB to answer a question two ints already answer.
        val adjustments = FilterEngine.seedFrom(PhotoFilters.SOFT_FILM, measure)
        val a = PreviewFilterSpec.of(PhotoFilters.SOFT_FILM, adjustments)
        val b = PreviewFilterSpec.of(PhotoFilters.SOFT_FILM, adjustments)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a.lut !== b.lut)
        assertTrue(a != PreviewFilterSpec.of(PhotoFilters.NIGHT_STREET, adjustments))
        assertTrue(a != PreviewFilterSpec.of(PhotoFilters.SOFT_FILM, adjustments.copy(exposure = 3)))
    }
}
