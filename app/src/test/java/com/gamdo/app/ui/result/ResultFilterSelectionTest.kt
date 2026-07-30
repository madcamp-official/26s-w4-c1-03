package com.gamdo.app.ui.result

import com.gamdo.app.data.ResultFilterKind
import com.gamdo.app.data.ResultFilterStateHolder
import com.gamdo.app.data.preset.ColorParams
import com.gamdo.app.data.preset.Composition
import com.gamdo.app.data.preset.ResolvedStyle
import com.gamdo.app.edit.LocalFilter
import com.gamdo.app.ui.reference.ReferenceLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **P1-B2** — the result strip has one source of truth.
 *
 * The screen used to hold two private copies of what the strip shows: the item
 * list came from a `ResolvedStyle?` parameter kept in a `remember` at the nav
 * host, and the selection from a `remember` inside the screen. Both die with
 * their composition, so an Activity recreation dropped the `내 감도` slot and a
 * trip to the album dropped the selection — while `ResultFilterStateHolder`,
 * which hangs off `AppContainer` and therefore outlives both, held the right
 * answer and had no reader anywhere in the app.
 *
 * Moving the store is a Compose change and cannot run here. The *rules* that sit
 * on top of it can, and they are where the two new hazards live: an app-scoped
 * selection can now leak from one photo into the next, and every id the holder
 * emits has to mean something to the pipeline.
 */
class ResultFilterSelectionTest {

    // ---- what each catalogue id means -------------------------------------

    /**
     * The coupling test. P2 owns the catalogue and may grow it; the screen maps
     * every id it receives onto a pipeline decision. If those two drift, a new
     * item silently renders as whatever the `else` branch happened to be.
     */
    @Test
    fun `every id the holder can emit maps to the matching strip selection`() {
        val holder = ResultFilterStateHolder()
        holder.synchronizeReference(colourReference())
        val items = holder.state.value.items

        // 원본 + six presets + the active reference.
        assertEquals(8, items.size)
        items.forEach { item ->
            val expected = when (item.kind) {
                ResultFilterKind.ORIGINAL -> StripSelection.ORIGINAL
                ResultFilterKind.PRESET -> StripSelection.PRESET
                ResultFilterKind.REFERENCE -> StripSelection.REFERENCE
            }
            assertEquals("id=${item.id}", expected, stripSelectionFor(item.id))
        }
    }

    /**
     * An id this build cannot resolve must land on 원본, never on PRESET.
     *
     * The difference is not cosmetic. `PRESET` switches the geometry and optical
     * passes on for a device photo ([correctionPassesFor]), so a stale
     * `sessions.style_preset_id` naming a since-removed preset would crop and
     * re-expose a photo out of the user's own library under a label the screen
     * could not even print.
     */
    @Test
    fun `an unrecognised id is treated as 원본, not as a preset`() {
        assertEquals(StripSelection.ORIGINAL, stripSelectionFor("retired_preset"))
        assertEquals(LocalFilter.ORIGINAL, localFilterFor("retired_preset"))
        assertEquals(
            CorrectionPasses(geometry = false, optical = false, style = false),
            correctionPassesFor(
                EditSourceKind.DEVICE_PHOTO,
                stylePickFor(
                    EditSourceKind.DEVICE_PHOTO,
                    stripSelectionFor("retired_preset"),
                    chosenByUser = true,
                ),
            ),
        )
    }

    /**
     * The reference slot runs `QuickFilterEditor` on ORIGINAL's identity recipe —
     * its colour is folded into the plan by `LocalEditor`, so a second colour pass
     * here would apply the look twice. But the *record* has to say REFERENCE, or a
     * saved edit cannot be reopened as what it was.
     */
    @Test
    fun `the reference slot renders as identity but records as REFERENCE`() {
        val id = ResultFilterStateHolder.REFERENCE_FILTER_ID
        assertEquals(LocalFilter.ORIGINAL, localFilterFor(id))
        assertEquals("REFERENCE", editRecordFilterName(id))
        assertEquals("NIGHT_STREET", editRecordFilterName("night_street"))
        assertEquals("ORIGINAL", editRecordFilterName(LocalFilter.ORIGINAL.filter.id))
    }

    // ---- what a photo opens on --------------------------------------------

    /**
     * The hazard the app-scoped store introduces, and the reason [selectionOnOpen]
     * exists at all.
     *
     * `ResultFilterState.selectedId` now outlives the screen. Edit an app capture
     * in 밤거리, go back, tap a photo out of the device library, and without a
     * per-photo reset that photo opens in 밤거리 — a look nobody picked for it,
     * applied to someone's own library photo, which is precisely what O-12 forbids.
     */
    @Test
    fun `a device photo never inherits the selection left by the previous photo`() {
        assertEquals(
            LocalFilter.ORIGINAL.filter.id,
            selectionOnOpen(
                source = EditSourceKind.DEVICE_PHOTO,
                userHasChosen = false,
                currentSelectedId = "night_street",
                hasActiveReferenceColor = true,
                sessionPresetId = "night_street",
                profilePresetId = "clean_social",
            ),
        )
    }

    /**
     * A device photo stays on 원본 even with a 내 감도 active. The reference is a
     * look the user chose *for their own photographs*, and O-12's subject is whose
     * photo this is, not whether a look exists.
     */
    @Test
    fun `an active reference does not open a device photo on itself`() {
        listOf(false, true).forEach { referenceFirst ->
            assertEquals(
                LocalFilter.ORIGINAL.filter.id,
                openingFilterId(
                    source = EditSourceKind.DEVICE_PHOTO,
                    hasActiveReferenceColor = true,
                    sessionPresetId = null,
                    profilePresetId = null,
                    referenceFirst = referenceFirst,
                ),
            )
        }
    }

    /** O-15, unchanged: an app capture opens on the preset the shot was framed in. */
    @Test
    fun `an app capture opens on its shot-time preset`() {
        assertEquals(
            "night_street",
            openingFilterId(
                source = EditSourceKind.APP_CAPTURE,
                hasActiveReferenceColor = false,
                sessionPresetId = "night_street",
                profilePresetId = "clean_social",
            ),
        )
        assertEquals(
            "clean_social",
            openingFilterId(
                source = EditSourceKind.APP_CAPTURE,
                hasActiveReferenceColor = false,
                sessionPresetId = null,
                profilePresetId = "clean_social",
            ),
        )
        assertEquals(
            LocalFilter.ORIGINAL.filter.id,
            openingFilterId(
                source = EditSourceKind.APP_CAPTURE,
                hasActiveReferenceColor = false,
                sessionPresetId = null,
                profilePresetId = null,
            ),
        )
    }

    /**
     * **An active 내 감도 does not outrank the preset the shot was framed in.**
     * Owner decision, 2026-07-30, and the one most likely to be reverted by
     * accident — so this test exists to say why before someone does.
     *
     * `docs/P2_실기기_기능수정기록_2026-07-29.md`'s P2-B3 fixes the priority the other
     * way (`활성 레퍼런스 색감 > 세션 프리셋 > 온보딩 추천`), and P2's own
     * `recommendedDefaultFilterId` computes exactly that. Reading only P2's side,
     * this screen looks broken.
     *
     * It is not, and the reason is O-13/O-14 rather than anything about references.
     * A preset is **colour** now, and O-14 put that colour in the live preview — the
     * user watches the look while framing and presses the shutter on what they see.
     * Opening the photo in a different colour than it was taken in is a defect
     * however good the other colour is. The 내 감도 slot is one tap away.
     *
     * If the decision is ever revisited, [ACTIVE_REFERENCE_OPENS_APP_CAPTURES] is
     * the whole change — the `true` branch below is already correct.
     */
    @Test
    fun `an active reference does not beat the shot-time preset on an app capture`() {
        val openedOn = { referenceFirst: Boolean ->
            openingFilterId(
                source = EditSourceKind.APP_CAPTURE,
                hasActiveReferenceColor = true,
                sessionPresetId = "night_street",
                profilePresetId = "clean_social",
                referenceFirst = referenceFirst,
            )
        }
        // The decision: 밤거리 was on screen at the shutter, so 밤거리 opens.
        assertFalse(ACTIVE_REFERENCE_OPENS_APP_CAPTURES)
        assertEquals(
            "night_street",
            openingFilterId(
                source = EditSourceKind.APP_CAPTURE,
                hasActiveReferenceColor = true,
                sessionPresetId = "night_street",
                profilePresetId = "clean_social",
            ),
        )
        assertEquals("night_street", openedOn(false))
        // P2-B3's ordering, ready and deliberately not switched on.
        assertEquals(ResultFilterStateHolder.REFERENCE_FILTER_ID, openedOn(true))
    }

    /**
     * With no session preset and no profile preset, a capture opens on 원본 — an
     * active reference does not fill that hole either. Same decision, at the end of
     * the chain rather than the front of it.
     */
    @Test
    fun `an active reference does not fill in for a capture with no preset at all`() {
        assertEquals(
            LocalFilter.ORIGINAL.filter.id,
            openingFilterId(
                source = EditSourceKind.APP_CAPTURE,
                hasActiveReferenceColor = true,
                sessionPresetId = null,
                profilePresetId = null,
            ),
        )
    }

    /**
     * A tap outlives every later re-resolution of the opening preset.
     *
     * The screen resolves that preset asynchronously — `captures` row, then
     * `sessions` row, then `style_preset_id` — so the answer changes at least once
     * after the first frame. The effect that seeded the selection re-ran on every
     * change and re-seeded unconditionally, so a filter tapped inside that window
     * was silently undone under the user's finger.
     */
    @Test
    fun `a filter the user tapped survives the late-arriving session preset`() {
        assertEquals(
            "soft_film",
            selectionOnOpen(
                source = EditSourceKind.APP_CAPTURE,
                userHasChosen = true,
                currentSelectedId = "soft_film",
                hasActiveReferenceColor = false,
                sessionPresetId = "night_street",
                profilePresetId = "clean_social",
            ),
        )
    }

    /** The same protection for the reference slot, which is what B2 was reported about. */
    @Test
    fun `a tapped reference survives the late-arriving session preset`() {
        assertEquals(
            ResultFilterStateHolder.REFERENCE_FILTER_ID,
            selectionOnOpen(
                source = EditSourceKind.APP_CAPTURE,
                userHasChosen = true,
                currentSelectedId = ResultFilterStateHolder.REFERENCE_FILTER_ID,
                hasActiveReferenceColor = true,
                sessionPresetId = "night_street",
                profilePresetId = "clean_social",
            ),
        )
    }

    // ---- the catalogue is not a render outcome -----------------------------

    /**
     * B2's headline requirement, observed from the side that consumes it: 기본 6종은
     * 항상 유지된다 — 렌더가 실패해도, 미리보기 Bitmap이 null이어도.
     *
     * `ResultFilterStateHolderTest` pins this on the producer for one outcome; this
     * pins it for all three, and for the derived values the *screen* reads, which is
     * where the list used to be rebuilt.
     */
    @Test
    fun `no render outcome and no selection changes what the strip shows`() {
        val holder = ResultFilterStateHolder()
        holder.synchronizeReference(colourReference())
        val catalogue = holder.state.value.items

        holder.renderStarted()
        holder.select(ResultFilterStateHolder.REFERENCE_FILTER_ID)
        holder.renderFailed()
        assertEquals(catalogue, holder.state.value.items)
        assertTrue(hasReferenceSlot(holder.state.value))

        holder.select("night_street")
        holder.renderSucceeded()
        assertEquals(catalogue, holder.state.value.items)
        assertTrue(hasReferenceSlot(holder.state.value))
        assertEquals(6 + 1, catalogue.count { it.kind != ResultFilterKind.REFERENCE })
    }

    /**
     * A 구도만 reference has no colour for this screen to apply, so it gets no slot —
     * and the six presets are still all there. (`ResultFilterStateHolder` decides
     * this; the test is here because the screen's `hasReferenceSlot` is what acts
     * on it.)
     */
    @Test
    fun `a composition-only reference keeps the six presets and offers no slot`() {
        val holder = ResultFilterStateHolder()
        holder.synchronizeReference(
            colourReference().copy(referenceScope = ResolvedStyle.ReferenceScope.COMPOSITION),
        )
        val state = holder.state.value
        assertFalse(hasReferenceSlot(state))
        assertEquals(7, state.items.size)
    }

    // ---- P1-B3: the two 내 감도 words ---------------------------------------

    /**
     * 만들기 and 적용 must not read the same word — **P1-B3**.
     *
     * The strip label is not P2's `displayName`: the catalogue calls the applied
     * slot 내 감도, which is the `+`'s own word, and on device that produced a strip
     * whose only 내 감도 was the button that creates them. Whatever wording the
     * owner settles on, this is the property that has to survive it, so the screen
     * routes labels through [stripLabelFor] rather than printing `displayName`.
     */
    @Test
    fun `making a 감도 and applying one do not read the same`() {
        assertNotEquals(ReferenceLabels.CREATE, ReferenceLabels.ACTIVE)

        val holder = ResultFilterStateHolder()
        holder.synchronizeReference(colourReference())
        val reference = holder.state.value.items.first { it.kind == ResultFilterKind.REFERENCE }

        assertNotEquals(ReferenceLabels.CREATE, stripLabelFor(reference))
        assertEquals(ReferenceLabels.ACTIVE, stripLabelFor(reference))
    }

    /** Presets keep their own names — 밤거리 stays 밤거리 on the strip and on the badge. */
    @Test
    fun `presets keep the name the camera showed`() {
        val holder = ResultFilterStateHolder()
        val items = holder.state.value.items
        assertEquals("원본", stripLabelFor(items.first { it.id == LocalFilter.ORIGINAL.filter.id }))
        assertEquals(
            LocalFilter.NIGHT_STREET.label,
            stripLabelFor(items.first { it.id == "night_street" }),
        )
    }

    private fun colourReference() = ResolvedStyle(
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
    )
}
