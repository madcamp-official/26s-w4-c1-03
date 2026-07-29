package com.gamdo.app.core

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * W3.5-1 — [AppPermissions.PhotoAccessLevel.of] classifies the current media-read
 * grant into what the album screen should treat as full/partial/no access, per API
 * level. Android 14 (API 34+) introduced [Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED]
 * ("select photos") as a real third state that must not collapse into either FULL or
 * NONE — see remain_plan.md W3.5-1.
 */
class AppPermissionsTest {

    private val legacyStorage = Manifest.permission.READ_EXTERNAL_STORAGE
    private val fullImages = Manifest.permission.READ_MEDIA_IMAGES
    private val partialSelected = Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED

    // ---- API <= 32: only READ_EXTERNAL_STORAGE exists, no partial tier ----

    @Test
    fun `API 29 with storage permission granted is FULL`() {
        val level = AppPermissions.PhotoAccessLevel.of(sdkInt = 29, granted = setOf(legacyStorage))
        assertEquals(AppPermissions.PhotoAccessLevel.FULL, level)
    }

    @Test
    fun `API 29 with nothing granted is NONE`() {
        val level = AppPermissions.PhotoAccessLevel.of(sdkInt = 29, granted = emptySet())
        assertEquals(AppPermissions.PhotoAccessLevel.NONE, level)
    }

    @Test
    fun `API 32 with storage permission granted is FULL`() {
        val level = AppPermissions.PhotoAccessLevel.of(sdkInt = 32, granted = setOf(legacyStorage))
        assertEquals(AppPermissions.PhotoAccessLevel.FULL, level)
    }

    // ---- API 33: only READ_MEDIA_IMAGES exists, still no partial tier ----

    @Test
    fun `API 33 with images permission granted is FULL`() {
        val level = AppPermissions.PhotoAccessLevel.of(sdkInt = 33, granted = setOf(fullImages))
        assertEquals(AppPermissions.PhotoAccessLevel.FULL, level)
    }

    @Test
    fun `API 33 with nothing granted is NONE`() {
        val level = AppPermissions.PhotoAccessLevel.of(sdkInt = 33, granted = emptySet())
        assertEquals(AppPermissions.PhotoAccessLevel.NONE, level)
    }

    @Test
    fun `API 33 holding only the 34+ selected-photos permission is still NONE`() {
        // Should not happen in practice (the system does not grant this permission
        // below 34), but the classifier must not accidentally treat it as PARTIAL —
        // API 33 has no partial tier at all.
        val level = AppPermissions.PhotoAccessLevel.of(sdkInt = 33, granted = setOf(partialSelected))
        assertEquals(AppPermissions.PhotoAccessLevel.NONE, level)
    }

    // ---- API 34+: partial access is a real, distinct state ----

    @Test
    fun `API 34 with full images permission granted is FULL regardless of the partial permission`() {
        val level = AppPermissions.PhotoAccessLevel.of(sdkInt = 34, granted = setOf(fullImages))
        assertEquals(AppPermissions.PhotoAccessLevel.FULL, level)
    }

    @Test
    fun `API 34 with only the selected-photos permission is PARTIAL, not NONE`() {
        val level = AppPermissions.PhotoAccessLevel.of(sdkInt = 34, granted = setOf(partialSelected))
        assertEquals(AppPermissions.PhotoAccessLevel.PARTIAL, level)
    }

    @Test
    fun `API 34 with both permissions granted is FULL`() {
        val level = AppPermissions.PhotoAccessLevel.of(sdkInt = 34, granted = setOf(fullImages, partialSelected))
        assertEquals(AppPermissions.PhotoAccessLevel.FULL, level)
    }

    @Test
    fun `API 34 with neither permission is NONE`() {
        val level = AppPermissions.PhotoAccessLevel.of(sdkInt = 34, granted = emptySet())
        assertEquals(AppPermissions.PhotoAccessLevel.NONE, level)
    }
}
