package com.gamdo.app.edit

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.sin

/**
 * Times one interactive preview pass and splits the time between the stages of
 * [FilterEngine.apply].
 *
 * ## Why this exists
 *
 * The editor's filter strip and its adjustment ruler both feel slow on device, and
 * the plausible causes are not the same size: a per-pixel loop over a 1.7 MP preview,
 * a per-pixel allocation inside that loop, a re-decode of the source file, or the
 * Compose side re-uploading a new bitmap every frame. Guessing between them is how
 * you optimise the wrong stage. This harness answers the one question the JVM can
 * answer honestly — **what does the pure-Kotlin pixel pass cost, and which stage owns
 * it** — for the exact buffer size the screen actually renders at.
 *
 * ## What it does not measure
 *
 * `getPixels` / `setPixels` / `Bitmap.createBitmap` and the texture upload are
 * `android.graphics`, so they do not run here (there is no Robolectric in this
 * module); the `arraycopy` row bounds their memcpy half. And this is a desktop JVM:
 * absolute numbers are a **floor** for a 2019 ARM device, not a prediction of it. The
 * ratios between stages are the transferable part.
 *
 * Gated on `-Dgamdo.filterCost` so it never runs in a normal build.
 */
class FilterCostHarness {

    /** 4:5 capture (2904x3630) decoded at `EDITOR_DECODE_MAX_SIDE` = 1440. */
    private val width = 1152
    private val height = 1440
    private val pixelCount = width * height

    @Test
    fun preview_pass_cost_by_stage() {
        assumeTrue("set -Dgamdo.filterCost to run", System.getProperty("gamdo.filterCost") != null)
        val original = syntheticPhoto(width, height)
        println("cost: ${width}x$height = $pixelCount px (${pixelCount * 4 / 1024 / 1024} MB per buffer)")

        // Warm the JIT on every branch the ablations below take, or the first entry
        // absorbs compilation and reads several times slower than the same work later.
        repeat(4) {
            val warm = original.copyOf()
            FilterEngine.apply(warm, width, height, greenShift(), everything())
            FilterEngine.apply(warm, width, height, PhotoFilters.ORIGINAL)
        }

        println("cost: %-44s %8s".format("stage (cumulative)", "ms"))
        // Each row adds one stage to the row above it, so the difference between two
        // adjacent rows is what that stage costs.
        val setupBuffer = IntArray(1)
        row("setup only (LUTs, tone curve, hue table)", setupBuffer) { p ->
            // A one-pixel buffer runs the whole per-call setup and none of the loop.
            FilterEngine.apply(p, 1, 1, greenShift(), everything())
        }
        row("+ base loop (3 LUTs, luma, tone curve, pack)", original) { p ->
            FilterEngine.apply(p, width, height, PhotoFilters.ORIGINAL)
        }
        row("+ 생동감/채도", original) { p ->
            FilterEngine.apply(
                p, width, height, PhotoFilters.ORIGINAL,
                FilterEngine.Adjustments(vibrance = 20, saturation = 10),
            )
        }
        row("+ 색상 혼합, no hue shift", original) { p ->
            FilterEngine.apply(
                p, width, height, greenSatOnly(),
                FilterEngine.Adjustments(vibrance = 20, saturation = 10),
            )
        }
        row("+ 색상 혼합 WITH hue shift (rotateHue)", original) { p ->
            FilterEngine.apply(
                p, width, height, greenShift(),
                FilterEngine.Adjustments(vibrance = 20, saturation = 10),
            )
        }
        row("+ fade", original) { p ->
            FilterEngine.apply(
                p, width, height, greenShift(),
                FilterEngine.Adjustments(vibrance = 20, saturation = 10, fade = 18),
            )
        }
        row("+ grain", original) { p ->
            FilterEngine.apply(
                p, width, height, greenShift(),
                FilterEngine.Adjustments(vibrance = 20, saturation = 10, fade = 18, grain = 25),
            )
        }
        row("+ vignette (= everything)", original) { p ->
            FilterEngine.apply(p, width, height, greenShift(), everything())
        }

        println("cost: %-44s %8s".format("--- the six shipping presets, as seeded ---", ""))
        val measure = FilterEngine.measure(original)
        for (filter in PhotoFilters.ALL) {
            val seeded = FilterEngine.seedFrom(filter, measure)
            row(filter.id, original) { p -> FilterEngine.apply(p, width, height, filter, seeded) }
        }

        println(
            "cost: %-44s %8s".format(
                "--- the same six across ${defaultFilterSlices()} cores ---", "",
            ),
        )
        for (filter in PhotoFilters.ALL) {
            val seeded = FilterEngine.seedFrom(filter, measure)
            row("${filter.id} (parallel)", original) { p ->
                runBlocking { applyFilterInParallel(p, width, height, filter, seeded) }
            }
        }

        println("cost: %-44s %8s".format("--- reference points ---", ""))
        row("arraycopy of the preview buffer", original) { p ->
            System.arraycopy(p, 0, IntArray(pixelCount), 0, pixelCount)
        }
        row("FilterEngine.measure (the luma histogram)", original) { p ->
            FilterEngine.measure(p)
        }
        row("per-call setup x100 (LUTs, curve, hue tables)", setupBuffer) { p ->
            repeat(100) { FilterEngine.apply(p, 1, 1, greenShift(), everything()) }
        }
    }

    private fun greenSatOnly() = PhotoFilters.ORIGINAL.copy(
        hsl = listOf(PhotoFilter.HueAdjust(HueBand.GREEN, saturation = 39)),
    )

    private fun greenShift() = PhotoFilters.ORIGINAL.copy(
        hsl = listOf(PhotoFilter.HueAdjust(HueBand.GREEN, saturation = 39, hueShift = -19)),
    )

    private fun everything() = FilterEngine.Adjustments(
        exposure = 35, contrast = 18, highlights = -100, shadows = 100, whites = 40,
        blacks = 16, warmth = 9, tint = -9, vibrance = 20, saturation = 10,
        fade = 18, grain = 25, vignette = -14,
    )

    /** Best of five, because a stray GC pause is noise and the floor is the signal. */
    private fun row(label: String, source: IntArray, block: (IntArray) -> Unit): Long {
        var best = Long.MAX_VALUE
        repeat(5) {
            val pixels = source.copyOf()
            val started = System.nanoTime()
            block(pixels)
            val ms = (System.nanoTime() - started) / 1_000_000
            if (ms < best) best = ms
        }
        println("cost: %-44s %8d".format(label, best))
        return best
    }

    /**
     * Something with the statistics of a photograph rather than a gradient: smooth
     * lighting, a full hue sweep, and noise. A flat or synthetic-looking buffer would
     * let the branch predictor and the hue-band skip do work the real thing cannot.
     */
    private fun syntheticPhoto(width: Int, height: Int): IntArray {
        val pixels = IntArray(width * height)
        var seed = 12345
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
