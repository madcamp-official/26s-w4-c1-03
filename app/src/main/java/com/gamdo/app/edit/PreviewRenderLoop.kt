package com.gamdo.app.edit

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * §4-2 interactive preview: render the newest request, **one render at a time**.
 *
 * ## The cost this exists to bound
 *
 * The adjustment ruler emits a new [FilterEngine.Adjustments] on every drag tick —
 * up to one per frame. The screen used to hang the preview off a `produceState`
 * keyed on that value, which means every tick started its own render of the whole
 * preview bitmap. Three things went wrong at once, and only the first is obvious:
 *
 *  1. **Every tick paid full price.** A 1440px preview is ~2.6M pixels; the filter
 *     is a per-pixel pass over all of them, and it ran once per tick whether or not
 *     the previous one had finished.
 *  2. **The superseded renders finished anyway.** `produceState` cancels the old
 *     coroutine when its key changes, but the pass itself is a `for` loop over an
 *     `IntArray` with no suspension point in it, so cancellation is not observed
 *     until it returns. The work completed, allocated its output, and was then
 *     discarded at the `withContext` boundary — paid for in full and thrown away.
 *  3. **They ran at the same time.** `Dispatchers.Default` is sized to the core
 *     count, so a fast drag put one full-frame pass on every core, each holding its
 *     own unpacking buffer and its own output bitmap. The renders that mattered
 *     were competing with the ones already superseded.
 *
 * [renderLatest] removes all three with one rule: **at most one render is in
 * flight, and whatever arrives while it runs collapses to the newest**. A burst of
 * sixty ticks becomes two renders — the one already running, and one more for where
 * the finger ended up.
 *
 * ## Why conflation rather than a debounce
 *
 * A debounce delays the *first* render by its window, so a single small nudge feels
 * laggy — and a nudge is the common case. Conflation renders immediately and only
 * skips frames the device could not have drawn anyway, so the loop self-tunes: a
 * fast machine renders more of the intermediate states, a slow one renders fewer,
 * and neither queues work it will not use. The last request is always rendered,
 * which is the property that matters — the picture the finger stops on is the
 * picture on screen.
 *
 * ## What is not fixed here
 *
 * Each surviving render still allocates its output bitmap and the previous one
 * still waits for the collector. Reusing the output in place is not available:
 * Compose redraws on *identity*, so mutating the bitmap it is already showing would
 * either not repaint or repaint mid-write. The unpacking buffer can be reused,
 * though, and [pixelBuffer] is how — safe precisely because this loop serialises
 * the renders.
 *
 * @param requests the stream of things to render; duplicates are dropped
 * @param render the expensive part, run one at a time
 * @param publish called with the request and its result, on the collecting context
 */
suspend fun <Q, R> renderLatest(
    requests: Flow<Q>,
    render: suspend (Q) -> R,
    publish: (Q, R) -> Unit,
) {
    requests
        // The whole fix. A conflated buffer holds exactly one pending request and
        // the newest wins, so the producer never blocks and the renderer never
        // works on a value the finger has already left behind.
        .conflate()
        // After the conflation, not before: here the comparison is against the
        // request that was actually *rendered*, so it also catches the case where a
        // drag wanders off a value and back onto it inside one render. A ruler held
        // against the end of its range keeps reporting the same number, and without
        // this each repeat would be a full re-render of an identical frame.
        .distinctUntilChanged()
        .collect { request -> publish(request, render(request)) }
}

/**
 * A pixel buffer of exactly `width * height` ints, reusing [existing] when it
 * already is that size.
 *
 * `Bitmap.getPixels` needs somewhere to unpack to, and for a 1440px preview that
 * somewhere is a 10 MB `IntArray` — the same order as the output bitmap itself, so
 * allocating one per render doubles what a drag costs the collector. The buffer is
 * fully overwritten by `getPixels` before it is read, so handing the same one back
 * carries nothing from the previous render.
 *
 * **The caller owns the serialisation.** Two renders sharing a buffer would write
 * over each other's pixels, so a buffer may only be reused by a loop that runs one
 * render at a time — [renderLatest] is that loop. Anything else passes null and
 * gets a fresh array.
 */
fun pixelBuffer(existing: IntArray?, width: Int, height: Int): IntArray {
    require(width > 0 && height > 0) { "size must be positive" }
    val needed = width * height
    return if (existing != null && existing.size == needed) existing else IntArray(needed)
}
