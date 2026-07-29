package com.gamdo.app.edit

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/**
 * What the interactive preview pass is allowed to cost, expressed as properties a
 * unit test can actually decide.
 *
 * ## The defect these pin
 *
 * The editor's filter strip and its adjustment ruler were both visibly slow on
 * device. `FilterCostHarness` measured why: one pass over the 1152x1440 preview
 * buffer costs 47–117 ms **on a desktop JVM**, and the largest single stage was
 * 색상 혼합 — inside which every pixel whose hue fell in an adjusted band paid a
 * `sin`, a `cos` and a three-element array allocation, for a rotation angle that has
 * only 360 possible values.
 *
 * ## Why these tests and not a stopwatch
 *
 * A wall-clock assertion in a 720-test suite is a flake generator, and the number it
 * would assert is a desktop number that says nothing about the phone. So the timing
 * stays in the gated harness and what runs here are the two properties that make the
 * speed-up *safe*:
 *
 *  - the precomputed rotation table is the same arithmetic it replaced, and
 *  - splitting the buffer across cores produces the identical image.
 *
 * Plus a golden checksum per preset, which is the guard that none of this changed
 * how a photograph looks.
 */
class FilterEngineCostTest {

    private val width = 160
    private val height = 200

    // ---- the optimisation must not change a single pixel ----------------------

    /**
     * Checksums of the six looks over [deterministicPhoto], captured from the engine
     * **before** the rotation table and the slice parameters existed.
     *
     * If one of these moves, the optimisation changed what a photograph looks like,
     * which is the one thing it was not allowed to do.
     */
    private val goldens = mapOf(
        "original" to 2003988043L,
        "clean_social" to 340074046L,
        "candid_feed" to 1319492752L,
        "bright_review" to 415271405L,
        "soft_film" to 1978268498L,
        "casual_portrait" to 1994279678L,
        "night_street" to 1988235001L,
    )

    @Test
    fun `every preset is byte-identical to the pre-optimisation engine`() {
        val original = deterministicPhoto()
        val measure = FilterEngine.measure(original)
        val actual = PhotoFilters.ALL.associate { filter ->
            val pixels = original.copyOf()
            FilterEngine.apply(pixels, width, height, filter, FilterEngine.seedFrom(filter, measure))
            filter.id to checksum(pixels)
        }
        actual.forEach { (id, sum) -> println("golden: \"$id\" to ${sum}L,") }
        assertEquals(goldens, actual)
    }

    // ---- the rotation table is the arithmetic it replaced ----------------------

    /**
     * The per-pixel rotation was a YIQ-style matrix built from `sin`/`cos` of the
     * band's hue shift. The angle comes from `hueTable`'s per-degree array, so it has
     * 360 possible values and the matrix can be built 360 times instead of 1.7
     * million times — but only if it is the *same* matrix.
     *
     * The reference formula is written out here rather than shared with the engine on
     * purpose: a table that agrees with itself proves nothing.
     */
    @Test
    fun `the precomputed rotation matrices match the per-pixel formula`() {
        val rows = listOf(
            PhotoFilter.HueAdjust(HueBand.GREEN, saturation = 39, hueShift = -19),
            PhotoFilter.HueAdjust(HueBand.BLUE, hueShift = 44),
        )
        val hueShift = FilterEngine.hueTable(rows)[1]
        val table = FilterEngine.hueRotationMatrices(hueShift)

        assertEquals(360 * 9, table.size)
        for (bin in 0 until 360) {
            val expected = referenceRotation(hueShift[bin])
            for (k in 0 until 9) {
                assertEquals(
                    "bin $bin coefficient $k (shift ${hueShift[bin]})",
                    expected[k].toDouble(),
                    table[bin * 9 + k].toDouble(),
                    0.0,
                )
            }
        }
    }

    @Test
    fun `a zero hue shift leaves the colour untouched`() {
        val table = FilterEngine.hueRotationMatrices(FloatArray(360))
        val identity = referenceRotation(0f)
        for (k in 0 until 9) {
            assertEquals(identity[k].toDouble(), table[k].toDouble(), 1e-6)
        }
    }

    // ---- the buffer may be split across cores ---------------------------------

    @Test
    fun `filtering the buffer in slices produces the identical image`() {
        val original = deterministicPhoto()
        val measure = FilterEngine.measure(original)
        val filter = PhotoFilters.CLEAN_SOCIAL
        val adjustments = FilterEngine.seedFrom(filter, measure)

        val whole = original.copyOf()
        FilterEngine.apply(whole, width, height, filter, adjustments)

        val sliced = original.copyOf()
        for (bounds in sliceBounds(sliced.size, width, slices = 7)) {
            FilterEngine.apply(
                sliced, width, height, filter, adjustments,
                fromIndex = bounds.first,
                toIndex = bounds.last + 1,
            )
        }
        assertArrayEquals(whole, sliced)
    }

    /**
     * The position-dependent stages are the reason this is not obvious: grain is
     * hashed from the pixel's index and the vignette from `index % width`, so a slice
     * that renumbered its own pixels from zero would produce a seam. This asserts the
     * seam is absent for the two effects that could produce one.
     */
    @Test
    fun `slicing does not move the grain or the vignette`() {
        val original = deterministicPhoto()
        val adjustments = FilterEngine.Adjustments(grain = 60, vignette = -60)

        val whole = original.copyOf()
        FilterEngine.apply(whole, width, height, PhotoFilters.ORIGINAL, adjustments)

        val sliced = original.copyOf()
        for (bounds in sliceBounds(sliced.size, width, slices = 4)) {
            FilterEngine.apply(
                sliced, width, height, PhotoFilters.ORIGINAL, adjustments,
                fromIndex = bounds.first,
                toIndex = bounds.last + 1,
            )
        }
        assertArrayEquals(whole, sliced)
    }

    @Test
    fun `slice bounds cover every pixel exactly once and start on a row`() {
        for (slices in 1..9) {
            val bounds = sliceBounds(width * height, width, slices)
            assertEquals(0, bounds.first().first)
            assertEquals(width * height - 1, bounds.last().last)
            bounds.zipWithNext { a, b ->
                assertEquals("contiguous", a.last + 1, b.first)
            }
            bounds.forEach { assertTrue("non-empty", it.last >= it.first) }
            bounds.drop(1).forEach { assertEquals("row aligned", 0, it.first % width) }
        }
    }

    @Test
    fun `a single slice is the whole buffer`() {
        val bounds = sliceBounds(width * height, width, slices = 1)
        assertEquals(1, bounds.size)
        assertEquals(0..(width * height - 1), bounds.single())
    }

    @Test
    fun `an image smaller than the slice count is not split into empty slices`() {
        val bounds = sliceBounds(3 * 2, width = 3, slices = 8)
        assertTrue("no empty slice", bounds.all { it.last >= it.first })
        assertEquals(0, bounds.first().first)
        assertEquals(5, bounds.last().last)
    }

    // ---- the parallel entry point agrees with the serial one -------------------

    @Test
    fun `the parallel pass produces the identical image`() = runBlocking {
        val original = deterministicPhoto()
        val measure = FilterEngine.measure(original)
        for (filter in PhotoFilters.ALL) {
            val adjustments = FilterEngine.seedFrom(filter, measure)
            val serial = original.copyOf()
            FilterEngine.apply(serial, width, height, filter, adjustments)

            val parallel = original.copyOf()
            applyFilterInParallel(parallel, width, height, filter, adjustments, slices = 6)
            assertArrayEquals("${filter.id} disagrees", serial, parallel)
        }
    }

    // ---- helpers ---------------------------------------------------------------

    /** The YIQ-style rotation the per-pixel code used, restated independently. */
    private fun referenceRotation(deg: Float): FloatArray {
        val lr = 0.2126f
        val lg = 0.7152f
        val lb = 0.0722f
        val rad = deg * Math.PI.toFloat() / 180f
        val c = kotlin.math.cos(rad)
        val s = sin(rad)
        return floatArrayOf(
            lr + c * (1 - lr) - s * lr,
            lg - c * lg - s * lg,
            lb - c * lb + s * (1 - lb),
            lr - c * lr + s * 0.143f,
            lg + c * (1 - lg) + s * 0.140f,
            lb - c * lb - s * 0.283f,
            lr - c * lr - s * (1 - lr),
            lg - c * lg + s * lg,
            lb + c * (1 - lb) + s * lb,
        )
    }

    private fun checksum(pixels: IntArray): Long {
        var h = 1125899906842597L
        for (p in pixels) h = 31 * h + p
        return h and 0x7fffffffL
    }

    /** Deterministic and photograph-shaped: smooth light, a full hue sweep, noise. */
    private fun deterministicPhoto(): IntArray {
        val pixels = IntArray(width * height)
        var seed = 987654321
        for (y in 0 until height) {
            for (x in 0 until width) {
                seed = seed * 1103515245 + 12345
                val noise = (seed ushr 24) and 0x1f
                val fx = x.toFloat() / width
                val fy = y.toFloat() / height
                val r = (120 + 90 * sin(fx * 6.0f) + noise).toInt().coerceIn(0, 255)
                val g = (130 + 80 * sin(fy * 5.0f + 1.1f) + noise).toInt().coerceIn(0, 255)
                val b = (110 + 95 * sin((fx + fy) * 4.0f + 2.2f) + noise).toInt().coerceIn(0, 255)
                pixels[y * width + x] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return pixels
    }
}
