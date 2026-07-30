package com.gamdo.app.ui.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The camera screen's open/armed rules (owner's final UI redesign, 2026-07-30).
 *
 * These are the assertions that would otherwise need a Compose UI test. The module
 * has none, so the rules were extracted into [CameraPanels] and are checked here.
 */
class CameraPanelTest {

    private val everyMode = CameraOverlayMode.entries

    // ---- toggle ----------------------------------------------------------------

    @Test
    fun `tapping a button opens its panel`() {
        assertEquals(
            CameraOverlayMode.FILTER_SHEET,
            CameraPanels.toggled(CameraOverlayMode.NONE, CameraOverlayMode.FILTER_SHEET),
        )
    }

    @Test
    fun `re-tapping the active button closes it — the one cancel gesture`() {
        for (mode in everyMode - CameraOverlayMode.NONE) {
            assertEquals(
                "re-tapping $mode's own button must close it; that is the single " +
                    "cancel vocabulary the whole screen uses",
                CameraOverlayMode.NONE,
                CameraPanels.toggled(mode, mode),
            )
        }
    }

    @Test
    fun `opening any one closes the other two`() {
        for (from in everyMode) {
            for (to in everyMode - CameraOverlayMode.NONE) {
                if (from == to) continue
                assertEquals(
                    "$from -> $to must land on $to and nothing else",
                    to,
                    CameraPanels.toggled(from, to),
                )
            }
        }
    }

    /**
     * The requirement this stands for: "활성 표시는 버튼의 앰버 하나로, 필터·프레임
     * 패널은 닫는다" (§4 P2-1). Arming the lasso while the filter sheet covers the
     * bottom of the preview would leave the drawing surface partly unreachable.
     */
    @Test
    fun `arming area-select closes the filter sheet`() {
        assertEquals(
            CameraOverlayMode.AREA_SELECT,
            CameraPanels.toggled(CameraOverlayMode.FILTER_SHEET, CameraOverlayMode.AREA_SELECT),
        )
        assertFalse(CameraPanels.sheetVisible(CameraOverlayMode.AREA_SELECT))
    }

    // ---- the rule most likely to be undone by accident -------------------------

    @Test
    fun `picking a filter does not close the sheet`() {
        assertEquals(
            "the redesign is explicit: 필터를 골라도 시트가 닫히지 않는다. A filter is a " +
                "colour (O-13), and comparing two colours means seeing both against the " +
                "same live scene.",
            CameraOverlayMode.FILTER_SHEET,
            CameraPanels.filterPicked(CameraOverlayMode.FILTER_SHEET),
        )
    }

    @Test
    fun `picking a filter changes nothing whatever the mode`() {
        for (mode in everyMode) {
            assertEquals(mode, CameraPanels.filterPicked(mode))
        }
    }

    // ---- scrim -----------------------------------------------------------------

    @Test
    fun `tapping outside a sheet closes it`() {
        assertEquals(
            CameraOverlayMode.NONE,
            CameraPanels.scrimTapped(CameraOverlayMode.FILTER_SHEET),
        )
        assertEquals(
            CameraOverlayMode.NONE,
            CameraPanels.scrimTapped(CameraOverlayMode.SETTINGS_SHEET),
        )
    }

    /**
     * There is no scrim in area-select — the preview *is* the drawing surface — so a
     * scrim tap must be inexpressible there rather than merely unwired. Otherwise
     * the first stroke of a lasso cancels the lasso.
     */
    @Test
    fun `a scrim tap cannot cancel area-select`() {
        assertEquals(
            CameraOverlayMode.AREA_SELECT,
            CameraPanels.scrimTapped(CameraOverlayMode.AREA_SELECT),
        )
        assertEquals(CameraOverlayMode.NONE, CameraPanels.scrimTapped(CameraOverlayMode.NONE))
    }

    // ---- the shutter stays live ------------------------------------------------

    @Test
    fun `a sheet is a picker, not a modal`() {
        assertTrue(CameraPanels.sheetVisible(CameraOverlayMode.FILTER_SHEET))
        assertTrue(CameraPanels.sheetVisible(CameraOverlayMode.SETTINGS_SHEET))
        assertFalse(CameraPanels.sheetVisible(CameraOverlayMode.NONE))
        assertFalse(CameraPanels.sheetVisible(CameraOverlayMode.AREA_SELECT))
    }

    @Test
    fun `area-select is armed only in its own mode`() {
        for (mode in everyMode) {
            assertEquals(
                mode == CameraOverlayMode.AREA_SELECT,
                CameraPanels.areaSelectArmed(mode),
            )
        }
    }

    // ---- the debug gate --------------------------------------------------------

    @Test
    fun `the settings sheet exists in debug builds only`() {
        assertTrue(CameraPanels.settingsSheetAvailable(isDebugBuild = true))
        assertFalse(CameraPanels.settingsSheetAvailable(isDebugBuild = false))
    }

    /**
     * The gate must agree with the HUD's, because the sheet's only content is the
     * HUD toggle. A demo build showing an empty 설정 sheet would be a control that
     * does nothing.
     */
    @Test
    fun `the settings gate is the HUD's gate, not a second copy of it`() {
        for (debug in listOf(true, false)) {
            assertEquals(
                DebugHudGate.availableIn(debug),
                CameraPanels.settingsSheetAvailable(debug),
            )
        }
    }

    /**
     * `rememberSaveable` survives process death, so a bundle written by a debug
     * build can be restored into a build with no settings sheet.
     */
    @Test
    fun `a restored settings mode collapses in a build without the sheet`() {
        assertEquals(
            CameraOverlayMode.NONE,
            CameraPanels.resolve(CameraOverlayMode.SETTINGS_SHEET, isDebugBuild = false),
        )
        assertEquals(
            CameraOverlayMode.SETTINGS_SHEET,
            CameraPanels.resolve(CameraOverlayMode.SETTINGS_SHEET, isDebugBuild = true),
        )
    }

    @Test
    fun `resolve leaves every other mode alone in every build`() {
        for (mode in everyMode - CameraOverlayMode.SETTINGS_SHEET) {
            for (debug in listOf(true, false)) {
                assertEquals(mode, CameraPanels.resolve(mode, debug))
            }
        }
    }

    // ---- durations -------------------------------------------------------------

    @Test
    fun `the redesign's durations are the ones compiled in`() {
        assertEquals("sheet slide up/down", 260, CAMERA_SHEET_ANIM_MS)
        assertEquals("bracket and shutter white to amber", 200, CAMERA_ALIGN_FADE_MS)
    }
}
