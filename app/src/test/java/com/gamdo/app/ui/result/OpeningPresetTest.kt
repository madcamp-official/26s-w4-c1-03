package com.gamdo.app.ui.result

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which preset an app capture opens on (**O-15 (1)**).
 *
 * The screen used to open on `settingsRepository.getStylePresetId()` — the preset
 * chosen during onboarding. That was deliberate while a preset meant *guide*: a
 * mid-session camera pick configured that one shot and had no business outliving
 * it (TEAM.md §8).
 *
 * O-13 changed what a preset is. It is now **colour**, and colour is a property of
 * the photograph. So shooting with 밤거리 on screen and opening the result in
 * 깔끔한 소셜 is a defect, not a policy — and O-14's preview colour makes it
 * visible rather than merely true, because the user will have *watched* the frame
 * in 밤거리 before pressing the shutter.
 *
 * The value is already recorded: `CameraScreen` writes `stylePresetId = preset.id`
 * into `sessions` at capture time. Nothing new is stored; the screen reads the
 * right row.
 *
 * Device photos are untouched — O-12 keeps them on `원본`, and that is decided by
 * `opensOnPreferredStyle`, not here.
 */
class OpeningPresetTest {

    @Test
    fun `the session's preset wins over the onboarding profile`() {
        assertEquals("night_street", openingPresetId("night_street", "clean_social"))
    }

    /**
     * A capture with no session — `Captures.sessionId` is nullable and its own
     * comment marks NULL as a gallery import — has no shot-time preset to inherit,
     * so the profile is the only answer left. Falling through to `null` here would
     * open every imported photo on `원본` regardless of what the user set up.
     */
    @Test
    fun `a capture with no session falls back to the profile`() {
        assertEquals("clean_social", openingPresetId(null, "clean_social"))
    }

    /**
     * A session exists but recorded no preset. `Sessions.stylePresetId` is nullable
     * and `GuideKpiRepository.openSession` accepts null, so this is reachable — a
     * session opened before any preset was resolved. Same answer as no session:
     * there is nothing to inherit.
     */
    @Test
    fun `a session without a recorded preset falls back to the profile`() {
        assertEquals("clean_social", openingPresetId(null, "clean_social"))
    }

    @Test
    fun `with neither, there is nothing to open on`() {
        assertNull(openingPresetId(null, null))
    }

    /**
     * The profile is never consulted when the session has an answer, **even if the
     * session's preset is one the app no longer ships.** Resolving an unknown id is
     * `LocalFilter.forPresetId`'s job and it already answers `ORIGINAL`; silently
     * substituting the profile here would mean a photo shot on a since-removed
     * preset quietly opens on a different look, which is the exact class of
     * surprise this change exists to remove.
     */
    @Test
    fun `an unrecognised session preset is still preferred over the profile`() {
        assertEquals("retired_preset", openingPresetId("retired_preset", "clean_social"))
    }

    @Test
    fun `a blank session preset is treated as absent, not as an id`() {
        assertEquals("clean_social", openingPresetId("", "clean_social"))
        assertEquals("clean_social", openingPresetId("   ", "clean_social"))
    }
}
