package com.gamdo.app.ui.camera

import com.gamdo.app.edit.FilterEngine
import com.gamdo.app.edit.PhotoFilter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * **Rejected. This is not the preview path — see
 * [com.gamdo.app.camera.gl.PreviewColorEffect].**
 *
 * ## What this is for now
 *
 * O-14 chose an exact GLES pipeline over this approximation, on the strength of
 * what this class measures. It is kept, with no production caller, because the
 * measurement is the argument: `PreviewFilterModelTest`'s
 * `the fitted colour matrix is the thing this replaces` fits a matrix here, runs it
 * against [FilterEngine], and asserts the error is still large — **153 levels over
 * the RGB cube and 90 on the grey ramp for `clean_social`** — while the shader model
 * beside it stays within 3.
 *
 * Those two figures are the **exposure-omitted** fit, because that is the recipe
 * the shader ships (O-15 (2)). [PreviewColorMatrixTest] fits the same preset with
 * exposure at slider 25 and reports **154 / 89**; both are correct for their own
 * seed, and the seed is the only thing that separates them. A matrix figure quoted
 * without one is not reproducible — which is why both tests now pin theirs.
 *
 * That keeps O-14's premise executable rather than a claim in a decision table. If
 * someone ever makes a matrix good enough, that test fails, and the GL pipeline and
 * its second EGL context can be deleted. Until then this file is the reason they
 * exist.
 *
 * Everything below describes the rejected design and is left intact as the record of
 * it. The 입자/비네팅 and exposure caveats it lists are real, and two of them
 * survived into the chosen path for the same underlying reasons — see
 * [com.gamdo.app.camera.gl.PreviewFilterSpec.previewAdjustments] for exposure and
 * [com.gamdo.app.camera.gl.grainAt] for grain.
 *
 * ---
 *
 * O-13 (1) — the preset's colour, expressed as the one operator a live camera
 * preview can actually run.
 *
 * ## Why a matrix, and why fitted rather than written
 *
 * The editor's colour is [FilterEngine]: linear-light white balance and exposure, a
 * 512-sample tone curve applied to luminance, vibrance, an eight-band colour mixer,
 * then grain and vignette. None of that can run per preview frame — it is a
 * per-pixel `IntArray` pass on the CPU, and the analysis pipeline is already the
 * budget's tightest customer.
 *
 * What every candidate preview path *can* run is a 4×5 colour matrix:
 * `RenderEffect.createColorFilterEffect(ColorMatrixColorFilter)`, a `TextureView`
 * layer `Paint`, and a `CameraEffect` fragment shader all take one, and all three
 * apply it for free — it is a handful of ALU ops on a fragment that is already
 * being written.
 *
 * So the matrix is **fitted to [FilterEngine] by least squares** rather than
 * hand-authored. That is the difference between "derived from the same preset
 * parameters the editor uses" and a lookalike: nobody tunes these twenty numbers,
 * and editing a preset in `PhotoFilters` moves the preview automatically. What it
 * cannot promise is agreement — see [fidelity], which measures the disagreement in
 * 8-bit levels instead of asserting there is none.
 *
 * ## What is dropped on purpose
 *
 * **입자 and 비네팅.** Both are functions of pixel *position*, and a colour matrix
 * has no position. They are excluded from the fit rather than smeared into a global
 * tint — see the `grain and vignette are dropped rather than faked` test. So a
 * preview of `soft_film` has that preset's tone and colour but not its grain, and
 * `night_street` is not darkened at the corners. 페이드 is affine and survives
 * exactly.
 *
 * **Exposure.** [FilterEngine.seedFrom] caps a preset's published exposure by the
 * highlight headroom it measures **in the photograph**. The preview has no such
 * measurement, so the caller supplies one; the matrix is recomputed only when the
 * preset changes, not per frame. On a scene brighter than the assumed measure the
 * saved file therefore receives *less* exposure lift than the preview showed.
 */
object PreviewColorMatrix {

    /** `android.graphics.ColorMatrix`'s length: four rows of five. */
    const val LENGTH = 20

    /** Row-major 4×5 identity, in `ColorMatrix` convention (offsets in 0..255). */
    val IDENTITY = floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    /** Fit lattice: 9³ = 729 colours. */
    private const val FIT_STEPS = 9

    /** Check lattice: 16³ = 4096 colours, deliberately *not* a superset of the fit. */
    private const val CHECK_STEPS = 16

    /**
     * Worst-case and RMS disagreement between the matrix and [FilterEngine], in
     * 8-bit levels.
     *
     * Two lattices because they answer different questions. `cube` is unweighted
     * over the whole RGB cube and is pessimistic — it counts a fully saturated
     * primary as heavily as the near-neutrals that make up most of a real frame.
     * `grey` is the neutral ramp, which is where the tone curve lives and what the
     * eye reads as "the photo got brighter / flatter".
     */
    data class Fidelity(
        val maxCubeError: Float,
        val rmsCubeError: Float,
        val maxGreyError: Float,
        val rmsGreyError: Float,
    )

    /**
     * Least-squares fit of `(R, G, B, 1) → (R', G', B')` against what
     * [FilterEngine.apply] does to the same colours.
     *
     * [adjustments] is the caller's — the same object the editor's sliders hold —
     * with 입자 and 비네팅 forced to zero because they are positional.
     */
    fun fit(filter: PhotoFilter, adjustments: FilterEngine.Adjustments): FloatArray {
        val input = lattice(FIT_STEPS)
        val output = render(filter, adjustments, input)

        // Normal equations for the 4-term basis, shared across the three outputs.
        val ata = Array(4) { DoubleArray(4) }
        val aty = Array(4) { DoubleArray(3) }
        val basis = DoubleArray(4)
        for (i in input.indices) {
            val p = input[i]
            basis[0] = ((p shr 16) and 0xff) / 255.0
            basis[1] = ((p shr 8) and 0xff) / 255.0
            basis[2] = (p and 0xff) / 255.0
            basis[3] = 1.0
            val q = output[i]
            val target = doubleArrayOf(
                ((q shr 16) and 0xff) / 255.0,
                ((q shr 8) and 0xff) / 255.0,
                (q and 0xff) / 255.0,
            )
            for (r in 0 until 4) {
                for (c in 0 until 4) ata[r][c] += basis[r] * basis[c]
                for (c in 0 until 3) aty[r][c] += basis[r] * target[c]
            }
        }

        val coefficients = solve(ata, aty)
        val matrix = IDENTITY.copyOf()
        for (channel in 0 until 3) {
            val row = channel * 5
            matrix[row] = coefficients[0][channel].toFloat()
            matrix[row + 1] = coefficients[1][channel].toFloat()
            matrix[row + 2] = coefficients[2][channel].toFloat()
            matrix[row + 3] = 0f
            // The constant term is in 0..1; ColorMatrix wants its offset in 0..255.
            matrix[row + 4] = (coefficients[3][channel] * 255.0).toFloat()
        }
        return matrix
    }

    /** How far [matrix] is from what [FilterEngine] would have done. */
    fun fidelity(
        filter: PhotoFilter,
        adjustments: FilterEngine.Adjustments,
        matrix: FloatArray,
    ): Fidelity {
        val cube = error(filter, adjustments, matrix, lattice(CHECK_STEPS))
        val grey = error(filter, adjustments, matrix, greyRamp())
        return Fidelity(
            maxCubeError = cube.first,
            rmsCubeError = cube.second,
            maxGreyError = grey.first,
            rmsGreyError = grey.second,
        )
    }

    /** Applies [matrix] the way the GPU would: affine, then clamp. */
    fun applyTo(matrix: FloatArray, argb: Int): Int {
        val r = ((argb shr 16) and 0xff).toFloat()
        val g = ((argb shr 8) and 0xff).toFloat()
        val b = (argb and 0xff).toFloat()
        val a = ((argb shr 24) and 0xff).toFloat()
        fun channel(row: Int): Int {
            val o = row * 5
            val v = matrix[o] * r + matrix[o + 1] * g + matrix[o + 2] * b + matrix[o + 3] * a + matrix[o + 4]
            return v.roundToInt().coerceIn(0, 255)
        }
        return (argb and -0x1000000) or (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
    }

    // --- internals -------------------------------------------------------------

    /**
     * [FilterEngine]'s answer for [input], with the positional effects removed.
     *
     * `width = size, height = 1` is irrelevant once 비네팅 is zero — it is the only
     * consumer of the geometry — and 입자 is index-keyed, so zeroing both makes the
     * pass a pure function of colour, which is the premise a matrix fit needs.
     */
    private fun render(
        filter: PhotoFilter,
        adjustments: FilterEngine.Adjustments,
        input: IntArray,
    ): IntArray {
        val pixels = input.copyOf()
        FilterEngine.apply(
            pixels = pixels,
            width = pixels.size,
            height = 1,
            filter = filter,
            adjustments = adjustments.copy(grain = 0, vignette = 0),
        )
        return pixels
    }

    private fun error(
        filter: PhotoFilter,
        adjustments: FilterEngine.Adjustments,
        matrix: FloatArray,
        input: IntArray,
    ): Pair<Float, Float> {
        val expected = render(filter, adjustments, input)
        var worst = 0f
        var squares = 0.0
        var count = 0
        for (i in input.indices) {
            val actual = applyTo(matrix, input[i])
            for (shift in intArrayOf(16, 8, 0)) {
                val d = abs(((expected[i] shr shift) and 0xff) - ((actual shr shift) and 0xff)).toFloat()
                worst = max(worst, d)
                squares += d.toDouble() * d
                count++
            }
        }
        return worst to sqrt(squares / max(count, 1)).toFloat()
    }

    private fun lattice(steps: Int): IntArray {
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

    /** Gaussian elimination with partial pivoting; [rhs] carries three columns. */
    private fun solve(lhs: Array<DoubleArray>, rhs: Array<DoubleArray>): Array<DoubleArray> {
        val n = 4
        val a = Array(n) { lhs[it].copyOf() }
        val y = Array(n) { rhs[it].copyOf() }
        for (col in 0 until n) {
            var pivot = col
            for (r in col + 1 until n) if (abs(a[r][col]) > abs(a[pivot][col])) pivot = r
            if (pivot != col) {
                val ta = a[col]; a[col] = a[pivot]; a[pivot] = ta
                val ty = y[col]; y[col] = y[pivot]; y[pivot] = ty
            }
            val d = a[col][col]
            // A full RGB lattice is always full rank, so this is a guard against a
            // degenerate caller rather than an expected branch: fall back to the
            // identity row instead of producing NaN, which on a preview would be a
            // black screen with no message.
            if (abs(d) < 1e-12) return identityCoefficients()
            for (c in col until n) a[col][c] /= d
            for (c in 0 until 3) y[col][c] /= d
            for (r in 0 until n) {
                if (r == col) continue
                val f = a[r][col]
                if (f == 0.0) continue
                for (c in col until n) a[r][c] -= f * a[col][c]
                for (c in 0 until 3) y[r][c] -= f * y[col][c]
            }
        }
        return y
    }

    private fun identityCoefficients(): Array<DoubleArray> = arrayOf(
        doubleArrayOf(1.0, 0.0, 0.0),
        doubleArrayOf(0.0, 1.0, 0.0),
        doubleArrayOf(0.0, 0.0, 1.0),
        doubleArrayOf(0.0, 0.0, 0.0),
    )
}
