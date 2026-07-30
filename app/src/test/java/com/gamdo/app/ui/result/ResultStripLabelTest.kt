package com.gamdo.app.ui.result

import com.gamdo.app.data.FilterRenderState
import com.gamdo.app.data.ResultFilterItem
import com.gamdo.app.data.ResultFilterKind
import com.gamdo.app.data.ResultFilterStateHolder
import com.gamdo.app.data.preset.ColorParams
import com.gamdo.app.data.preset.Composition
import com.gamdo.app.data.preset.ResolvedStyle
import com.gamdo.app.edit.LocalFilter
import com.gamdo.app.ui.reference.ReferenceLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * **P1-B3** — what the strip and the on-photo badge say the photo is in.
 *
 * Three of B3's five requirements were already met before this change and are not
 * re-litigated here: an app capture opens on the preset it was shot in (O-15,
 * `OpeningPresetTest`), 원본 stays on the strip as a one-tap comparison, and 만들기 /
 * 적용 stopped reading the same word once the owner settled the two labels on
 * 2026-07-30 (`ResultFilterSelectionTest`, which pins that they differ).
 *
 * What is here is the part those leave open:
 *
 *  - the settled wording itself, pinned as text, and the retirement of 레퍼런스 from
 *    everything a user can read;
 *  - **every** strip item naming the look it applies, not just the two that had a
 *    reported defect;
 *  - the badge naming what is *on the photo* rather than what is *selected*. Those
 *    are the same string until a render fails, and B3 asks for the first one —
 *    "무엇이 적용됐는지 알 수 있게 한다".
 */
class ResultStripLabelTest {

    // ---- the two words, as text ---------------------------------------------

    /**
     * The owner's wording, 2026-07-30, device-verified. Pinned as literals because
     * `ResultFilterSelectionTest` only pins that the two labels *differ*, which two
     * equally wrong words also satisfy. Changing either string should take an
     * explicit edit here rather than passing silently.
     */
    @Test
    fun `the two label strings are the ones the owner settled on`() {
        assertEquals("감도 만들기", ReferenceLabels.CREATE)
        assertEquals("내 감도", ReferenceLabels.ACTIVE)
    }

    /**
     * 레퍼런스 is gone from both strip labels. It is the internal name for AI 2 —
     * `ReferenceRepository`, `ResolvedStyle`, `activeReferenceStyle`, and still the
     * literal `displayName` that `ResolvedStyle.fromReference` stamps on every
     * analysis — and the product word is 감도, which is what onboarding taught.
     */
    @Test
    fun `neither label uses the internal word`() {
        listOf(ReferenceLabels.CREATE, ReferenceLabels.ACTIVE).forEach { label ->
            assertEquals("label=$label", false, label.contains("레퍼런스"))
        }
    }

    /**
     * The screen's word for the reference slot does not come from P2's catalogue.
     *
     * `ResultFilterStateHolder` happens to store 내 감도 as the reference item's
     * `displayName` today, so asserting the two are equal would pass without
     * [stripLabelFor] existing at all. Feeding it a deliberately wrong name is what
     * shows the screen is not reading that field — which matters because the field is
     * P2-owned and `ResolvedStyle.fromReference` still fills it with 내 레퍼런스.
     */
    @Test
    fun `the reference label does not come from P2's displayName`() {
        val mislabelled = ResultFilterItem(
            id = ResultFilterStateHolder.REFERENCE_FILTER_ID,
            kind = ResultFilterKind.REFERENCE,
            displayName = "내 레퍼런스",
        )
        assertEquals(ReferenceLabels.ACTIVE, stripLabelFor(mislabelled))
    }

    // ---- every item names its look ------------------------------------------

    /**
     * What the strip writes under each thumb, for every item the catalogue can hold.
     *
     * Derived, not transcribed: a preset's label is the recipe's own, so renaming
     * 밤거리 moves both together instead of failing here. What is asserted is the
     * *rule* — presets keep their names, and the one exception is the reference slot.
     */
    @Test
    fun `every strip item names the look it applies`() {
        val items = holderWithReference().state.value.items
        val labels = items.map(::stripLabelFor)

        assertEquals(LocalFilter.entries.map { it.label } + ReferenceLabels.ACTIVE, labels)

        // No id leaks through as a label, and nothing is blank.
        items.zip(labels).forEach { (item, label) ->
            assertNotEquals(item.id, label)
            assertEquals("id=${item.id}", false, label.isBlank())
        }
    }

    // ---- the badge describes the photo, not the selection --------------------

    /** With everything rendering, the badge and the highlighted thumb agree. */
    @Test
    fun `the badge names the selected look while it is on the photo`() {
        val holder = holderWithReference()
        holder.select(LocalFilter.NIGHT_STREET.filter.id)
        holder.renderSucceeded()
        assertEquals(
            LocalFilter.NIGHT_STREET.label,
            appliedStyleLabel(holder.state.value, referenceColorLanded = true),
        )
    }

    /**
     * The defect. `QuickFilterEditor` throws, the preview loop publishes null, and
     * the screen shows the untouched decode — while the strip correctly keeps 밤거리
     * selected, because a filter that will not render is not a reason to take the
     * other six away. The badge must not go on claiming 밤거리 is on the photo.
     */
    @Test
    fun `a preset whose render failed is not claimed on the badge`() {
        val holder = holderWithReference()
        holder.select(LocalFilter.NIGHT_STREET.filter.id)
        holder.renderFailed()

        assertEquals(LocalFilter.ORIGINAL.label, appliedStyleLabel(holder.state.value, true))
        // The strip is unchanged — this is a badge rule, not a catalogue rule.
        assertEquals(
            LocalFilter.NIGHT_STREET.filter.id,
            holder.state.value.selectedId,
        )
        assertEquals(
            LocalFilter.NIGHT_STREET.label,
            stripLabelFor(holder.state.value.items.first { it.id == holder.state.value.selectedId }),
        )
    }

    /**
     * A failure belongs to the filter it happened on. Moving to another preset that
     * renders fine must clear the badge's fallback, or one bad filter would make the
     * whole strip read 원본 for the rest of the session.
     */
    @Test
    fun `a failure on one preset does not follow the user to another`() {
        val holder = holderWithReference()
        holder.select(LocalFilter.NIGHT_STREET.filter.id)
        holder.renderFailed()
        holder.select(LocalFilter.SOFT_FILM.filter.id)

        // Still `Failed`, but recorded against 밤거리 — 필름 has not been tried yet.
        assertEquals(
            FilterRenderState.Failed(LocalFilter.NIGHT_STREET.filter.id),
            holder.state.value.renderState,
        )
        assertEquals(
            LocalFilter.SOFT_FILM.label,
            appliedStyleLabel(holder.state.value, referenceColorLanded = true),
        )
    }

    /**
     * The reference slot is exempt from the preset rule, and this is the assertion
     * that stops the fix from over-reaching.
     *
     * Its colour is rendered into the source bitmap by `LocalEditor`'s plan; the strip
     * recipe it rides on is `ORIGINAL`, an identity pass. That identity pass failing
     * removes nothing from the photo, so 내 감도 is still an honest badge.
     */
    @Test
    fun `a reference survives a failed strip render because its colour is in the plan`() {
        val holder = holderWithReference()
        holder.select(ResultFilterStateHolder.REFERENCE_FILTER_ID)
        holder.renderFailed()
        assertEquals(
            ReferenceLabels.ACTIVE,
            appliedStyleLabel(holder.state.value, referenceColorLanded = true),
        )
    }

    /**
     * What *does* take the reference off the photo: `LocalEditor` failing to produce a
     * plan, which `ResultScreen` recovers from by showing the untouched decode.
     */
    @Test
    fun `a reference whose plan never landed is not claimed on the badge`() {
        val holder = holderWithReference()
        holder.select(ResultFilterStateHolder.REFERENCE_FILTER_ID)
        holder.renderSucceeded()
        assertEquals(
            LocalFilter.ORIGINAL.label,
            appliedStyleLabel(holder.state.value, referenceColorLanded = false),
        )
    }

    /**
     * 원본 is the absence of a look, so no failure can take anything off it — and the
     * fallback must not be reached by an id the catalogue does not contain either,
     * which is possible whenever P2 grows or shrinks the list under a live selection.
     */
    @Test
    fun `original and unknown ids both read 원본`() {
        val holder = holderWithReference()
        holder.renderFailed(LocalFilter.ORIGINAL.filter.id)
        assertEquals(
            LocalFilter.ORIGINAL.label,
            appliedStyleLabel(holder.state.value, referenceColorLanded = true),
        )

        val stale = holder.state.value.copy(selectedId = "a_preset_this_build_removed")
        assertEquals(LocalFilter.ORIGINAL.label, appliedStyleLabel(stale, true))
    }

    private fun holderWithReference() = ResultFilterStateHolder().apply {
        synchronizeReference(
            ResolvedStyle(
                source = ResolvedStyle.Source.REFERENCE,
                sourceKey = "hash",
                displayName = "내 레퍼런스",
                composition = Composition(
                    "4:5",
                    listOf(0.3, 0.6),
                    "center",
                    listOf(0.1, 0.2),
                    0.5,
                    listOf(-5.0, 5.0),
                    "natural",
                    listOf(0.3, 0.6),
                ),
                color = ColorParams(5500.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
            ),
        )
    }
}
