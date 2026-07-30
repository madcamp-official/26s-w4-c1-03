package com.gamdo.app.ui.result

import com.gamdo.app.data.FilterRenderState
import com.gamdo.app.data.ResultFilterState
import com.gamdo.app.data.ResultFilterStateHolder
import com.gamdo.app.edit.LocalFilter
import com.gamdo.app.ui.rescue.RescueComparison
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W3.5-6 / O-12 — 기기 사진에는 자동 보정을 걸지 않는다.
 *
 * The owner decision this file exists for is not a preference, it is about doing
 * something to a photo the app did not take. So the branch is pulled out of Compose
 * and pinned here, where it actually runs: `ResultScreen.kt` holds a `Bitmap` and a
 * `Uri` and cannot execute under `testDebugUnitTest` at all (no androidTest source
 * set, no Robolectric).
 */
class ResultFlowDecisionsTest {

    // ---- O-12: what may touch the pixels, by source -------------------------

    @Test
    fun `device photo opens with every pass off — no rotation, no exposure, no white balance`() {
        val passes = correctionPassesFor(EditSourceKind.DEVICE_PHOTO, StylePick.NONE)

        assertFalse("geometry must not run on a photo the app did not take", passes.geometry)
        assertFalse("optical (exposure + white balance) must not run either", passes.optical)
        assertFalse(passes.style)
        assertFalse("the screen must show the untouched decode", passes.runsAnyPass)
    }

    @Test
    fun `app capture keeps today's behaviour — geometry and optical always run`() {
        val passes = correctionPassesFor(EditSourceKind.APP_CAPTURE, StylePick.NONE)

        assertTrue(passes.geometry)
        assertTrue(passes.optical)
        assertTrue(passes.runsAnyPass)
        assertFalse("the six presets ride on QuickFilterEditor, not the plan", passes.style)
    }

    @Test
    fun `device photo starts correcting the moment a preset is picked`() {
        val passes = correctionPassesFor(EditSourceKind.DEVICE_PHOTO, StylePick.PRESET)

        assertTrue("필터를 직접 고르면 그때부터 적용된다", passes.geometry)
        assertTrue(passes.optical)
    }

    @Test
    fun `tapping 원본 on a device photo keeps it untouched`() {
        // The label says 원본. Turning auto-exposure on underneath it would be the
        // same unasked-for correction O-12 forbids, one tap later.
        val passes = correctionPassesFor(EditSourceKind.DEVICE_PHOTO, StylePick.ORIGINAL)

        assertFalse(passes.geometry)
        assertFalse(passes.optical)
        assertFalse(passes.runsAnyPass)
    }

    @Test
    fun `the reference colour stage runs only when 내 레퍼런스 is picked`() {
        for (source in EditSourceKind.entries) {
            for (pick in StylePick.entries) {
                assertEquals(
                    "$source / $pick",
                    pick == StylePick.REFERENCE,
                    correctionPassesFor(source, pick).style,
                )
            }
        }
    }

    @Test
    fun `nothing a device photo can be in runs a pass before the user picks`() {
        // Total over the enum rather than four asserts: a StylePick added later
        // fails here until someone decides which side of O-12 it is on.
        val untouched = setOf(StylePick.NONE, StylePick.ORIGINAL)
        for (pick in StylePick.entries) {
            assertEquals(
                "DEVICE_PHOTO / $pick",
                pick !in untouched,
                correctionPassesFor(EditSourceKind.DEVICE_PHOTO, pick).runsAnyPass,
            )
        }
    }

    // ---- O-12's other half: the strip must not pre-pick a look --------------

    @Test
    fun `a device photo does not open on the saved style preset`() {
        assertFalse(opensOnPreferredStyle(EditSourceKind.DEVICE_PHOTO))
        assertEquals(StylePick.NONE, initialStylePick(EditSourceKind.DEVICE_PHOTO))
    }

    @Test
    fun `an app capture still opens on the saved style preset`() {
        assertTrue(opensOnPreferredStyle(EditSourceKind.APP_CAPTURE))
        assertEquals(StylePick.PRESET, initialStylePick(EditSourceKind.APP_CAPTURE))
    }

    @Test
    fun `on a device photo, a selection the user did not make is not a pick`() {
        for (selection in StripSelection.entries) {
            assertEquals(
                "$selection",
                StylePick.NONE,
                stylePickFor(EditSourceKind.DEVICE_PHOTO, selection, chosenByUser = false),
            )
        }
    }

    @Test
    fun `a selection the user did make maps straight through, whatever the source`() {
        for (source in EditSourceKind.entries) {
            assertEquals(StylePick.ORIGINAL, stylePickFor(source, StripSelection.ORIGINAL, true))
            assertEquals(StylePick.PRESET, stylePickFor(source, StripSelection.PRESET, true))
            assertEquals(StylePick.REFERENCE, stylePickFor(source, StripSelection.REFERENCE, true))
        }
    }

    // ---- 저장 ---------------------------------------------------------------

    @Test
    fun `a device photo edit is saved as a new file and nothing is written to the database`() {
        // There is no `captures` row to hang a `capture_edit_stack` step off, and
        // Room is frozen (R2) so one cannot be invented. The edit becomes a new
        // file; the user's original is only ever opened for reading.
        assertEquals(SaveTarget.NEW_FILE_ONLY, saveTargetFor(EditSourceKind.DEVICE_PHOTO))
    }

    @Test
    fun `an app capture edit still records its parameters`() {
        assertEquals(SaveTarget.CAPTURE_DERIVATIVE, saveTargetFor(EditSourceKind.APP_CAPTURE))
    }

    // ---- AI 3 -----------------------------------------------------------------

    @Test
    fun `AI로 보정 is not offered for a photo with no captures row`() {
        assertTrue(offersGenerativeRestore(EditSourceKind.DEVICE_PHOTO))
        assertTrue(offersGenerativeRestore(EditSourceKind.APP_CAPTURE))
    }

    // ---- 내 감도로 정리하기 (브리프 §13 결함 2) --------------------------------

    private fun state(
        recommendedDefaultFilterId: String,
        selectedId: String,
    ) = ResultFilterState(
        items = emptyList(),
        selectedId = selectedId,
        recommendedDefaultFilterId = recommendedDefaultFilterId,
        activeReference = null,
        renderState = FilterRenderState.Idle,
    )

    /**
     * The card applies the 감도 the holder resolved, not the reference slot by name.
     * A user who never analysed a reference photo still has a 감도 — the session
     * preset, then the onboarding one — and the card has to reach it, or it is the
     * dead tap the defect reported.
     */
    @Test
    fun `내 감도로 정리하기 applies the resolved default, reference or not`() {
        assertEquals(
            ResultFilterStateHolder.REFERENCE_FILTER_ID,
            localStyleFilterId(
                state(ResultFilterStateHolder.REFERENCE_FILTER_ID, selectedId = "clean_social"),
            ),
        )
        assertEquals(
            "night_street",
            localStyleFilterId(state("night_street", selectedId = LocalFilter.ORIGINAL.filter.id)),
        )
    }

    /**
     * Whether the tap is going to change anything is a fact the sheet is allowed to
     * know, so that "이미 적용돼 있어요" can be said instead of shown as silence. An app
     * capture opens on its session preset, so a user with no reference is already
     * sitting on their 감도 and the correct outcome is a no-op.
     */
    @Test
    fun `the card reports whether it would change the photo`() {
        assertTrue(
            localStyleChangesPhoto(
                state(ResultFilterStateHolder.REFERENCE_FILTER_ID, selectedId = "clean_social"),
            ),
        )
        assertFalse(
            localStyleChangesPhoto(state("night_street", selectedId = "night_street")),
        )
    }

    // ---- 후보 비교 (브리프 §8) -------------------------------------------------

    /**
     * A picked candidate wins over the strip: choosing one replaces the editor's
     * *source*, so the filter on top is a statement about the generated file, not
     * about the capture.
     */
    @Test
    fun `a picked candidate is what the photo is showing`() {
        assertEquals(
            RescueComparison.CANDIDATE,
            rescueComparisonFor(pickedCandidateId = "res_1", selectedFilterId = LocalFilter.ORIGINAL.filter.id),
        )
        assertEquals(
            RescueComparison.CANDIDATE,
            rescueComparisonFor(pickedCandidateId = "res_1", selectedFilterId = "night_street"),
        )
    }

    /**
     * With no pick the strip decides, and the only line that matters to the row is
     * 원본 versus a look of the user's — every preset and the reference slot alike
     * count as 현재 감도.
     */
    @Test
    fun `with no candidate the strip says original or 현재 감도`() {
        assertEquals(
            RescueComparison.ORIGINAL,
            rescueComparisonFor(pickedCandidateId = null, selectedFilterId = LocalFilter.ORIGINAL.filter.id),
        )
        assertEquals(
            RescueComparison.CURRENT_GAMDO,
            rescueComparisonFor(pickedCandidateId = null, selectedFilterId = "night_street"),
        )
        assertEquals(
            RescueComparison.CURRENT_GAMDO,
            rescueComparisonFor(
                pickedCandidateId = null,
                selectedFilterId = ResultFilterStateHolder.REFERENCE_FILTER_ID,
            ),
        )
    }

}
