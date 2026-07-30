package com.gamdo.app.ui.result

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.gamdo.app.ui.theme.Amber
import com.gamdo.app.ui.theme.TextHi
import com.gamdo.app.ui.theme.TextLow
import com.gamdo.app.ui.theme.TextMid
import kotlin.math.abs

/**
 * How the 보정 screen's dials and filter strip *look*, as functions of value and
 * selection — the owner's final redesign (Claude Design, 2026-07-30), 시안 06·07.
 *
 * ## Why this is a separate file
 *
 * This project has no `androidx.compose.ui.test`, no Robolectric and no `androidx.test`
 * runner, so anything written inside a `@Composable` is unreachable from the test source
 * set and can only be checked by eye on a device. The two rules the design states
 * numerically — the arc sweep and "grey at zero, amber otherwise" — are exactly the kind
 * that a plausible-looking edit breaks silently, so they are pulled out here where a
 * plain JUnit test can hold them.
 *
 * Nothing here imports `android.*`. Compose's [Color] and [FontWeight] are ordinary JVM
 * value classes from `compose-ui`, not framework types, so they run under the unit-test
 * JVM unstubbed — which means the test asserts the **real tokens** rather than an enum
 * standing in for them. An indirection layer would have moved the interesting mistake
 * (mapping the wrong token) back out of reach.
 */
object AdjustmentVisuals {

    /**
     * Degrees of arc a dial fills for [value] — the design's formula, verbatim:
     * `min(|v| × 3.6, 360)`.
     *
     * ## This is magnitude, not position, and that is the change
     *
     * The previous drawing used `EditTool.fraction(value)` — where the value sits inside
     * its own range — so on a bipolar control (밝기, −100..100) `−100` drew an empty dial,
     * `0` drew a **half-filled** one and `+100` drew a full one. Half-filled-at-rest is
     * what made the strip unreadable: eleven of the thirteen controls are bipolar, so an
     * untouched photo showed eleven half-full dials, and "this control is at its default"
     * looked identical to "this control is pushed halfway down".
     *
     * Under this formula rest is empty and any movement grows the arc from nothing, which
     * is what lets [valueColor]'s "grey at zero" mean anything at all — a grey half-circle
     * would still read as a filled gauge.
     *
     * The cost is real and deliberate: **the arc no longer carries the sign.** `−50` and
     * `+50` both sweep 180°. The sign lives in the number at the centre, which is
     * formatted `%+d` for precisely the bipolar controls where it matters. The arc answers
     * "how far from default", the number answers "which way".
     *
     * `|v| × 3.6` reaches 360 at `|v| = 100`, so the `min` only ever clamps a value
     * outside every [com.gamdo.app.edit.EditTool]'s range. It is kept because it is the
     * design's, and because a future control with a wider range should saturate rather
     * than wrap around past 12 o'clock and look like a small value again.
     */
    fun arcSweepDegrees(value: Int): Float = (abs(value) * 3.6f).coerceAtMost(360f)

    /**
     * The dial's ring and centre number: [TextLow] at rest, [Amber] once the control
     * holds a value.
     *
     * This is the "has a value" channel. It is deliberately *not* the same channel as
     * "is the control you are holding" ([labelColor]) — after choosing a filter almost
     * every control is non-zero, so if selection and non-zero shared a colour the whole
     * strip would light up and say nothing.
     */
    fun valueColor(value: Int): Color = if (value == 0) TextLow else Amber

    /** A dial's label: [TextHi] for the held control, [TextLow] for the rest. */
    fun labelColor(selected: Boolean): Color = if (selected) TextHi else TextLow

    /** A dial's label weight: 700 for the held control, 500 for the rest. */
    fun labelWeight(selected: Boolean): FontWeight =
        if (selected) FontWeight.Bold else FontWeight.Medium

    /**
     * A filter-strip tile's label: [Amber] when that filter is the selected one,
     * [TextMid] otherwise.
     *
     * Amber here is the *selection* meaning of the accent, the same one the 2dp ring
     * around the thumbnail carries — not a second use of it. The strip label is [TextMid]
     * rather than the dials' [TextLow] because it names a thing the user chooses between
     * rather than a value at rest; an unselected filter is still an offer.
     */
    fun stripLabelColor(selected: Boolean): Color = if (selected) Amber else TextMid

    /** A filter-strip tile's label weight: 700 when selected, 500 otherwise. */
    fun stripLabelWeight(selected: Boolean): FontWeight =
        if (selected) FontWeight.Bold else FontWeight.Medium
}
