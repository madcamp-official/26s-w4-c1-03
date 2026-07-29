package com.gamdo.app.ui.camera

import com.gamdo.app.edit.FilterEngine
import com.gamdo.app.edit.PhotoFilter
import com.gamdo.app.edit.PhotoFilters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O-13 (1) — how far a 4×5 colour matrix can carry a preset's look.
 *
 * The preview cannot run [FilterEngine]. Whatever the preview path ends up being —
 * `RenderEffect.createColorFilterEffect`, a `TextureView` layer paint, or a uniform
 * handed to a `CameraEffect` shader — the only colour operator all three accept on
 * this project's minSdk is an affine 4×5 matrix. Grain and vignette are per-pixel
 * *positional* and cannot appear in one at all; the tone curve, vibrance and the
 * colour mixer are non-linear and can only be approximated.
 *
 * So the question is not "does it look nice", it is **how much does the preview
 * disagree with the file the user will get**, in 8-bit levels. This test measures
 * that number rather than asserting a hand-picked look, and it measures it by
 * running the editor's own [FilterEngine] — the matrix is *fitted to the engine*,
 * so it cannot drift into a lookalike when someone edits a preset.
 *
 * Two error figures, because they answer different questions:
 *  - **cube** — unweighted over the whole RGB cube. Pessimistic: it weights
 *    fully-saturated primaries as heavily as the near-neutral colours that make up
 *    most of a real frame.
 *  - **grey** — the neutral ramp. This is where the tone curve lives, and it is
 *    what the eye actually reads as "the photo got brighter / flatter".
 */
class PreviewColorMatrixTest {

    /** A mid-bright scene, so the exposure ceiling behaves as it does on a real frame. */
    private val nominal = FilterEngine.Measure(meanLuma = 0.45f, p99 = 0.90f)

    private fun adjustmentsFor(filter: PhotoFilter) = FilterEngine.seedFrom(filter, nominal)

    @Test
    fun `the identity filter fits the identity matrix`() {
        val matrix = PreviewColorMatrix.fit(
            PhotoFilters.ORIGINAL,
            FilterEngine.Adjustments.NEUTRAL,
        )
        for (i in PreviewColorMatrix.IDENTITY.indices) {
            assertEquals(
                "matrix[$i]",
                PreviewColorMatrix.IDENTITY[i].toDouble(),
                matrix[i].toDouble(),
                1e-3,
            )
        }
    }

    @Test
    fun `the matrix is always the android ColorMatrix shape`() {
        val matrix = PreviewColorMatrix.fit(
            PhotoFilters.NIGHT_STREET,
            adjustmentsFor(PhotoFilters.NIGHT_STREET),
        )
        assertEquals(20, matrix.size)
        // Alpha row untouched: the preview must not become translucent.
        assertEquals(0f, matrix[15])
        assertEquals(0f, matrix[16])
        assertEquals(0f, matrix[17])
        assertEquals(1f, matrix[18])
        assertEquals(0f, matrix[19])
    }

    @Test
    fun `grain and vignette are dropped rather than faked`() {
        // A filter whose only content is positional effects must fit the identity.
        // If it did not, the fit would be smearing spatial noise into a global tint.
        val grainOnly = PhotoFilter(
            id = "grain_only",
            label = "grain",
            effects = PhotoFilter.Effects(grain = 100, vignette = -100),
        )
        val matrix = PreviewColorMatrix.fit(
            grainOnly,
            FilterEngine.Adjustments.NEUTRAL.copy(grain = 100, vignette = -100),
        )
        for (i in PreviewColorMatrix.IDENTITY.indices) {
            assertEquals(
                "matrix[$i]",
                PreviewColorMatrix.IDENTITY[i].toDouble(),
                matrix[i].toDouble(),
                1e-3,
            )
        }
    }

    @Test
    fun `fade is representable and survives the fit`() {
        // fade is an affine lift toward white, so unlike grain it belongs in the
        // matrix. It is the one 효과 the preview can honour exactly.
        val fadeOnly = PhotoFilter(id = "fade_only", label = "fade")
        val matrix = PreviewColorMatrix.fit(
            fadeOnly,
            FilterEngine.Adjustments.NEUTRAL.copy(fade = 100),
        )
        val fidelity = PreviewColorMatrix.fidelity(
            fadeOnly,
            FilterEngine.Adjustments.NEUTRAL.copy(fade = 100),
            matrix,
        )
        assertTrue(
            "fade should be near-exact, got max=${fidelity.maxCubeError}",
            fidelity.maxCubeError < 1.5f,
        )
        // ...and it must not be the identity, or the assertion above proves nothing.
        assertTrue(matrix[4] > 5f)
    }

    @Test
    fun `every shipped preset changes the matrix`() {
        val seen = mutableSetOf<String>()
        for (filter in PhotoFilters.ALL) {
            val matrix = PreviewColorMatrix.fit(filter, adjustmentsFor(filter))
            val key = matrix.joinToString(",") { "%.3f".format(it) }
            assertTrue("두 프리셋이 같은 행렬을 낸다: ${filter.id}", seen.add(key))
        }
    }

    /**
     * **The finding.** An affine matrix cannot follow the shipped presets, and this
     * records by how much.
     *
     * The first version of this test asserted the error stayed inside a budget I
     * guessed at (24 levels on the grey ramp). It does not — the measured worst case
     * is 89. So the test was turned around: it now pins the measurement itself, which
     * is the thing worth protecting. Going red here means one of
     *
     *  - a preset changed shape (a product decision — the preview's honesty moves with
     *    it), or
     *  - [FilterEngine] changed, or
     *  - somebody "improved" the fit, which for a least-squares optimum means they
     *    changed what is being fitted.
     *
     * All three want a human, none wants a widened tolerance.
     *
     * ## Why the numbers are this large
     *
     * The tone curve. `clean_social` publishes 어두운 영역 **+100**, which lifts pure
     * black to `SHADOW_CEILING` = 0.32 — 82 levels — while leaving anything above 0.6
     * alone. A straight line cannot both lift the floor by 82 levels and leave the
     * upper half where it is; it splits the difference and is wrong at both ends. The
     * decile profile below shows exactly that shape.
     */
    @Test
    fun `an affine matrix cannot follow the shipped presets`() {
        println("preset            cube-max  cube-rms  grey-max  grey-rms   (8-bit levels)")
        for (filter in PhotoFilters.ALL) {
            val adjustments = adjustmentsFor(filter)
            val matrix = PreviewColorMatrix.fit(filter, adjustments)
            val f = PreviewColorMatrix.fidelity(filter, adjustments, matrix)
            println(
                "%-16s %8.1f %9.1f %9.1f %9.1f".format(
                    filter.id, f.maxCubeError, f.rmsCubeError, f.maxGreyError, f.rmsGreyError,
                ),
            )
            val (cube, grey) = PINNED_MAX.getValue(filter.id)
            // Exact, not a tolerance. Every input here is deterministic — a fixed
            // lattice, a fixed seed, a pure engine — so there is nothing for a
            // tolerance to absorb except the drift this test exists to catch.
            assertEquals("${filter.id} cube worst case moved", cube, f.maxCubeError)
            assertEquals("${filter.id} grey-ramp worst case moved", grey, f.maxGreyError)
        }
    }

    /** Where on the neutral ramp the disagreement sits, so it can be judged by eye. */
    @Test
    fun `the grey-ramp error profile is printed for the record`() {
        println("preset            " + (0..10).joinToString(" ") { "%5d".format(it * 25) })
        for (filter in PhotoFilters.ALL) {
            val adjustments = adjustmentsFor(filter)
            val matrix = PreviewColorMatrix.fit(filter, adjustments)
            val row = (0..10).map { decile ->
                val v = (decile * 25).coerceAtMost(255)
                val argb = (0xff shl 24) or (v shl 16) or (v shl 8) or v
                val expected = engineGrey(filter, adjustments, v)
                val actual = (PreviewColorMatrix.applyTo(matrix, argb) shr 16) and 0xff
                actual - expected
            }
            println("%-16s ".format(filter.id) + row.joinToString(" ") { "%+5d".format(it) })
        }
        // Nothing to assert — this is the shape behind the numbers above. The
        // assertion that matters lives in the test before it.
        assertTrue(true)
    }

    private fun engineGrey(filter: PhotoFilter, adjustments: FilterEngine.Adjustments, v: Int): Int {
        val pixels = intArrayOf((0xff shl 24) or (v shl 16) or (v shl 8) or v)
        FilterEngine.apply(
            pixels = pixels,
            width = 1,
            height = 1,
            filter = filter,
            adjustments = adjustments.copy(grain = 0, vignette = 0),
        )
        return (pixels[0] shr 16) and 0xff
    }

    private companion object {
        /**
         * Worst (cube, grey-ramp) level, measured 2026-07-29 on `PhotoFilters` as
         * shipped, seeded from [nominal] — `Measure(0.45, 0.90)`.
         *
         * **The seed is part of the number.** `clean_social`'s grey-ramp worst case is
         * 89 here and 90 in `PreviewFilterModelTest`, and neither is wrong: that test
         * fits from `PreviewFilterSpec.previewAdjustments`, which omits exposure per
         * O-15 (2). The error is not monotonic in exposure, so the two seeds land in
         * different bands. Sweeping the exposure slider with everything else fixed:
         *
         * | slider | cube | grey |
         * |---|---|---|
         * | 0–10 | 153 | 90 |
         * | 12–16 | 154 | 90 |
         * | **18–28** | **154** | **89** |
         * | 30–32 | 154 | 90 |
         * | 34–40 | 153 | 90 |
         *
         * [nominal] puts `clean_social` at slider 25 — mid-band, but the band is only
         * ten units wide. That is exactly why the assertion below is exact and why the
         * seed is named here: a 1-level tolerance would have swallowed a move from 89
         * to 90 in silence, and two agents already spent three messages establishing
         * which of those digits belonged to which configuration.
         */
        val PINNED_MAX = mapOf(
            "original" to (0f to 0f),
            "clean_social" to (154f to 89f),
            "candid_feed" to (174f to 66f),
            "bright_review" to (171f to 70f),
            "soft_film" to (170f to 64f),
            "casual_portrait" to (179f to 61f),
            "night_street" to (173f to 67f),
        )
    }
}
