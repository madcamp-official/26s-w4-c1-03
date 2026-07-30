package com.gamdo.app.edit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Runs one [FilterEngine.apply] pass across several cores — **platform-free**, so
 * the split and the agreement between slices are both JVM-testable.
 *
 * ## Why this is not the mistake `PreviewRenderLoop` fixed
 *
 * `renderLatest` exists because a drag used to start a *new whole-frame render per
 * tick*, so `Dispatchers.Default` ended up running several already-superseded copies
 * of the same picture at once. That is the opposite of this: here there is still
 * exactly one render in flight — the two rules compose, and the invariant
 * `renderLatest` relies on (one render at a time, so the unpacking buffer can be
 * reused) is untouched, because [applyFilterInParallel] returns before the next
 * request is taken.
 *
 * ## Why splitting is safe at all
 *
 * The filter is a pure function of a pixel's value and its **index**: nothing reads a
 * neighbour. So disjoint index windows cannot interfere, and the only way to get a
 * seam is to renumber the pixels — which is why the window is a pair of absolute
 * indices into the same array rather than a copied sub-buffer. `FilterEngineCostTest`
 * asserts the parallel result is `assertArrayEquals`-identical to the serial one for
 * all six presets, and separately that grain and vignette (the two index-dependent
 * effects) do not move.
 *
 * ## What it costs
 *
 * Every slice rebuilds the per-call tables — three 256-entry channel LUTs, a
 * 512-sample tone curve, and the hue tables. That is under a millisecond against a
 * pass measured in tens, so it is paid rather than engineered around; keeping
 * [FilterEngine.apply] self-contained is worth more than the last few percent.
 *
 * ## Measure this in a non-debuggable build, or do not measure it
 *
 * On SM-G970N, one 1053×1315 preview pass (2026-07-30, 8 slices):
 *
 * | build | first render | warm |
 * |---|---|---|
 * | `debuggable = true` | 940ms | **~1000ms** |
 * | `debuggable = false` | 150ms | **29–55ms** |
 *
 * Nothing but the `debuggable` flag differs. ART restricts its optimising JIT for
 * debuggable apps, and this is a scalar per-pixel Kotlin loop, so it takes that
 * penalty at full strength — roughly **30×**. The identity filter costs the same as
 * `night_street`, which is the tell: the cost is the loop being interpreted, not
 * anything a recipe does.
 *
 * Two consequences, both easy to get wrong:
 *
 *  1. **A slow filter in a debug build is not evidence of a slow filter.** This was
 *     investigated once as a regression (owner report, 2026-07-30, "보정이 다시
 *     느려졌어") and the answer was the build type. Reproduce with
 *     `isDebuggable = false` in the debug block before changing any code here.
 *  2. **The Canvas path is immune and this one is not.** `LocalEditor.render`
 *     measured 159ms on the *same* debuggable build, because its work happens in
 *     Skia rather than in Kotlin. That is not an argument for moving the filters
 *     onto `ColorMatrix` — `PreviewColorMatrixTest` shows an affine matrix cannot
 *     follow these presets (up to 89 levels off on `clean_social`) — but it does
 *     explain why the two pipelines look so different under a debugger.
 */
suspend fun applyFilterInParallel(
    pixels: IntArray,
    width: Int,
    height: Int,
    filter: PhotoFilter,
    adjustments: FilterEngine.Adjustments = FilterEngine.Adjustments.NEUTRAL,
    slices: Int = defaultFilterSlices(),
) {
    if (pixels.isEmpty() || width <= 0) return
    val bounds = sliceBounds(pixels.size, width, slices)
    if (bounds.size == 1) {
        FilterEngine.apply(pixels, width, height, filter, adjustments)
        return
    }
    coroutineScope {
        bounds.map { window ->
            async(Dispatchers.Default) {
                FilterEngine.apply(
                    pixels = pixels,
                    width = width,
                    height = height,
                    filter = filter,
                    adjustments = adjustments,
                    fromIndex = window.first,
                    toIndex = window.last + 1,
                )
            }
        }.awaitAll()
    }
}

/**
 * How many pieces to cut a preview into.
 *
 * The core count, capped: past the number of physical cores the slices only compete,
 * and on the big.LITTLE parts this app targets the little cores finish late enough
 * that a few extra pieces would not fill the gap anyway.
 */
fun defaultFilterSlices(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)

/**
 * Splits `0 until [size]` into at most [slices] contiguous, non-empty, **row-aligned**
 * windows.
 *
 * Row alignment is not required for correctness — the filter reads no neighbour — but
 * it keeps each slice on whole cache lines of the same rows, and it makes a boundary
 * something a person can reason about when a seam is suspected.
 *
 * An image with fewer rows than [slices] comes back as fewer windows rather than as
 * empty ones.
 */
fun sliceBounds(size: Int, width: Int, slices: Int): List<IntRange> {
    require(size > 0) { "size must be positive" }
    require(width > 0) { "width must be positive" }
    require(slices > 0) { "slices must be positive" }
    val rows = (size + width - 1) / width
    val parts = slices.coerceAtMost(rows).coerceAtLeast(1)
    val windows = ArrayList<IntRange>(parts)
    var start = 0
    for (part in 1..parts) {
        val endRow = (part.toLong() * rows / parts).toInt()
        val end = (endRow * width).coerceAtMost(size)
        if (end > start) windows.add(start until end)
        start = end
    }
    if (windows.isEmpty()) windows.add(0 until size)
    return windows
}
