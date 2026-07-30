package com.gamdo.app.ui.result

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 시안 08's three correction tabs, and the one property that matters more than the
 * mapping: **a tab can never switch a pass on.**
 */
class CorrectionStageTest {

    private val all = CorrectionPasses(geometry = true, optical = true, style = true)

    @Test
    fun `the three stages read back off the passes they mean`() {
        assertEquals(CorrectionStage.NONE, stageOf(CorrectionPasses(false, false, false)))
        assertEquals(CorrectionStage.BASIC, stageOf(CorrectionPasses(true, true, false)))
        assertEquals(CorrectionStage.MOOD, stageOf(all))
    }

    @Test
    fun `narrowing to a stage produces the passes that stage names`() {
        assertEquals(CorrectionPasses(false, false, false), all.narrowedTo(CorrectionStage.NONE))
        assertEquals(CorrectionPasses(true, true, false), all.narrowedTo(CorrectionStage.BASIC))
        assertEquals(all, all.narrowedTo(CorrectionStage.MOOD))
    }

    /**
     * The guard the whole file exists for. Over every combination of passes and every
     * stage, narrowing may only remove — so no tab tap can turn a pass on that O-12 had
     * switched off.
     */
    @Test
    fun `narrowing never enables a pass that was off`() {
        for (geometry in booleanArrayOf(false, true)) {
            for (optical in booleanArrayOf(false, true)) {
                for (style in booleanArrayOf(false, true)) {
                    val allowed = CorrectionPasses(geometry, optical, style)
                    for (stage in CorrectionStage.entries) {
                        val got = allowed.narrowedTo(stage)
                        assertTrue(
                            "$allowed narrowed to $stage turned geometry on",
                            !got.geometry || allowed.geometry,
                        )
                        assertTrue(
                            "$allowed narrowed to $stage turned optical on",
                            !got.optical || allowed.optical,
                        )
                        assertTrue(
                            "$allowed narrowed to $stage turned style on",
                            !got.style || allowed.style,
                        )
                    }
                }
            }
        }
    }

    /**
     * O-12, expressed through the tabs: a device photo opens on 원본, and while it is on
     * 원본 there is nothing for any tab to enable. Tapping 무드 보정 on a just-opened
     * gallery photo must not start correcting it.
     */
    @Test
    fun `a device photo opens on 원본 and no tab can correct it before a filter is picked`() {
        assertEquals(CorrectionStage.NONE, initialCorrectionStage(EditSourceKind.DEVICE_PHOTO))
        val allowed = correctionPassesFor(EditSourceKind.DEVICE_PHOTO, initialStylePick(EditSourceKind.DEVICE_PHOTO))
        for (stage in CorrectionStage.entries) {
            assertFalse(
                "$stage corrected an untouched device photo",
                allowed.narrowedTo(stage).runsAnyPass,
            )
        }
    }

    /** An app capture keeps opening corrected, exactly as it did before the tabs existed. */
    @Test
    fun `an app capture opens on 기본 보정`() {
        assertEquals(CorrectionStage.BASIC, initialCorrectionStage(EditSourceKind.APP_CAPTURE))
    }

    /**
     * The reason [appliesStripColour] is not derivable from [CorrectionPasses.style]: for
     * a preset, `style` is false at *every* stage, so passes alone cannot tell 기본 보정
     * from 무드 보정.
     */
    @Test
    fun `only 무드 보정 lets the strip colour through`() {
        assertFalse(appliesStripColour(CorrectionStage.NONE))
        assertFalse(appliesStripColour(CorrectionStage.BASIC))
        assertTrue(appliesStripColour(CorrectionStage.MOOD))

        val presetPasses = correctionPassesFor(EditSourceKind.APP_CAPTURE, StylePick.PRESET)
        assertFalse(
            "a preset's colour does not travel in the style pass — see appliesStripColour",
            presetPasses.style,
        )
        assertEquals(
            "so 기본 보정 and 무드 보정 narrow a preset to the same passes",
            presetPasses.narrowedTo(CorrectionStage.BASIC),
            presetPasses.narrowedTo(CorrectionStage.MOOD),
        )
    }
}
