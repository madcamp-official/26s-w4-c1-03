package com.gamdo.app.ui.reference

import com.gamdo.app.data.ProfileRefinementRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 취향 더 정교하게 만들기 (요구사항 2026-07-30, 6bef31b) — the decisions the sheet
 * makes, pinned here because `ReferenceDetailSheet` holds a `Uri` and cannot run
 * under `testDebugUnitTest`.
 */
class ProfileRefineDecisionsTest {

    @Test
    fun `each state shows exactly one section`() {
        assertEquals(ProfileRefineSection.ACTION, refineSectionFor(ProfileRefineState.Idle))
        assertEquals(ProfileRefineSection.PROGRESS, refineSectionFor(ProfileRefineState.Analyzing(3)))
        assertEquals(ProfileRefineSection.DONE, refineSectionFor(ProfileRefineState.Done(3)))
        assertEquals(ProfileRefineSection.FAILED, refineSectionFor(ProfileRefineState.Failed(retryable = true)))
    }

    /**
     * The picker reports a cancel as an empty list, so "the user changed their mind"
     * and "the user picked nothing" arrive identically — and `refineFromPhotos`
     * throws on an empty list. Sending it would turn a cancel into a failure message.
     */
    @Test
    fun `an empty pick is never sent`() {
        assertFalse(canRefine(pickedCount = 0, state = ProfileRefineState.Idle))
        assertTrue(canRefine(pickedCount = 1, state = ProfileRefineState.Idle))
    }

    /** One refinement at a time — a second pick landing mid-analysis is dropped. */
    @Test
    fun `a run in flight blocks another`() {
        assertFalse(canRefine(pickedCount = 5, state = ProfileRefineState.Analyzing(5)))
        assertTrue(canRefine(pickedCount = 5, state = ProfileRefineState.Done(5)))
        assertTrue(canRefine(pickedCount = 5, state = ProfileRefineState.Failed(retryable = true)))
    }

    /**
     * An over-long pick is *not* rejected: the repository truncates to `MAX_PHOTOS`,
     * and refusing the whole selection because the user was enthusiastic would be
     * worse than using the first twenty.
     */
    @Test
    fun `an over-long pick is accepted and left to the repository to bound`() {
        assertTrue(canRefine(pickedCount = MAX_REFINE_PHOTOS + 5, state = ProfileRefineState.Idle))
    }

    /**
     * The picker must ask for the same number the repository will keep. If these ever
     * drift, the user picks photos that are silently dropped.
     */
    @Test
    fun `the picker bound is P2's own constant`() {
        assertEquals(ProfileRefinementRepository.MAX_PHOTOS, MAX_REFINE_PHOTOS)
    }

    /**
     * A missing onboarding profile is the one failure a second tap cannot fix, so it
     * is the one that must not offer 재시도.
     */
    @Test
    fun `only a retryable failure is worded as one`() {
        assertEquals("지금은 분석하지 못했어요", refineFailedMessage(retryable = true))
        assertEquals("먼저 온보딩에서 감도를 만들어 주세요", refineFailedMessage(retryable = false))
    }

    /**
     * The completion line claims the preference changed, not the photographs — as of
     * 2026-07-30 nothing reads `GamdoProfileV2` back, so a stronger promise would be
     * the same defect 결함 2 was. See the function's KDoc.
     */
    @Test
    fun `the done message claims the preference, not the photos`() {
        assertEquals("사진 4장을 취향에 반영했어요", refineDoneMessage(4))
    }
}
