package com.gamdo.app.ui.reference

import com.gamdo.app.data.ReferenceCreateState
import com.gamdo.app.data.preset.ResolvedStyle
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * AI 2 (내 감도 만들기) UI wiring — pure decisions only. Everything that needs a
 * Context, a Uri, or a Bitmap is Compose/Android glue and lives in
 * `ReferenceCreateSheet.kt` / camera+result strip wiring instead (untestable here:
 * no androidTest source set, no Robolectric — see integration task rule 1).
 *
 * One state branch is skipped on purpose: `ReferenceCreateState.AwaitingConsent`
 * carries an `android.net.Uri`, which cannot be constructed on a plain JVM unit
 * test without Robolectric (its methods are stubbed to throw). The exhaustive
 * `when` in [sheetSectionFor] still forces that branch to be handled correctly at
 * compile time even though this test cannot instantiate it — see DONE-DEVICE.
 */
class ReferenceFlowDecisionsTest {

    // ---- selectableReferenceScopes / defaultReferenceScope ----

    @Test
    fun `composition available offers all three scopes, BOTH first`() {
        assertEquals(
            listOf(
                ResolvedStyle.ReferenceScope.BOTH,
                ResolvedStyle.ReferenceScope.COMPOSITION,
                ResolvedStyle.ReferenceScope.COLOR,
            ),
            selectableReferenceScopes(compositionAvailable = true),
        )
    }

    @Test
    fun `composition unavailable — contract bans inventing a layout, only color is offered`() {
        assertEquals(
            listOf(ResolvedStyle.ReferenceScope.COLOR),
            selectableReferenceScopes(compositionAvailable = false),
        )
    }

    @Test
    fun `default scope is BOTH when selectable`() {
        assertEquals(ResolvedStyle.ReferenceScope.BOTH, defaultReferenceScope(compositionAvailable = true))
    }

    @Test
    fun `default scope degrades to COLOR when composition is unavailable`() {
        assertEquals(ResolvedStyle.ReferenceScope.COLOR, defaultReferenceScope(compositionAvailable = false))
    }

    // ---- clampReferenceOverlayAlpha ----

    @Test
    fun `overlay alpha default is 30 percent`() {
        assertEquals(0.30f, DEFAULT_REFERENCE_OVERLAY_ALPHA)
    }

    @Test
    fun `overlay alpha clamps below zero to zero`() {
        assertEquals(0f, clampReferenceOverlayAlpha(-0.4f))
    }

    @Test
    fun `overlay alpha clamps above the 60 percent ceiling`() {
        assertEquals(0.60f, clampReferenceOverlayAlpha(0.95f))
    }

    @Test
    fun `overlay alpha passes an in-range value through unchanged`() {
        assertEquals(0.42f, clampReferenceOverlayAlpha(0.42f))
    }

    // ---- buildFilterStrip (O-10 strip ordering) ----

    private val presets = listOf("clean_social", "candid_feed", "bright_review", "soft_film", "casual_portrait", "night_street")

    @Test
    fun `camera strip is create then presets, no AI restore slot`() {
        val strip = buildFilterStrip(presets, includeAiRestore = false, hasActiveReference = false)
        assertEquals(
            listOf<StripEntry<String>>(StripEntry.CreateReference) + presets.map { StripEntry.Preset(it) },
            strip,
        )
    }

    @Test
    fun `camera strip appends my-reference only while a reference is active`() {
        val strip = buildFilterStrip(presets, includeAiRestore = false, hasActiveReference = true)
        assertEquals(StripEntry.MyReference, strip.last())
        assertEquals(1 + presets.size + 1, strip.size)
    }

    @Test
    fun `result strip is create, ai-restore, then presets — O-10 order exactly`() {
        val strip = buildFilterStrip(presets, includeAiRestore = true, hasActiveReference = false)
        assertEquals(StripEntry.CreateReference, strip[0])
        assertEquals(StripEntry.AiRestore, strip[1])
        assertEquals(presets.map { StripEntry.Preset(it) }, strip.drop(2))
    }

    @Test
    fun `result strip with an active reference trails my-reference after the presets`() {
        val strip = buildFilterStrip(presets, includeAiRestore = true, hasActiveReference = true)
        assertEquals(2 + presets.size + 1, strip.size)
        assertEquals(StripEntry.MyReference, strip.last())
    }

    @Test
    fun `no active reference means no trailing slot at all`() {
        val strip = buildFilterStrip(presets, includeAiRestore = true, hasActiveReference = false)
        assertEquals(2 + presets.size, strip.size)
        org.junit.Assert.assertFalse(strip.contains(StripEntry.MyReference))
    }

    // ---- sheetSectionFor ----

    private fun sampleStyle() = ResolvedStyle(
        source = ResolvedStyle.Source.REFERENCE,
        sourceKey = "hash",
        displayName = "내 레퍼런스",
        composition = com.gamdo.app.data.preset.Composition(
            targetAspectRatio = "4:5",
            subjectScaleRange = listOf(0.25, 0.75),
            subjectPosition = "center",
            headroomRange = listOf(0.04, 0.24),
            horizonPosition = 0.5,
            cameraPitchRange = listOf(-5.0, 5.0),
            posePattern = "natural",
            backgroundRatio = listOf(0.25, 0.85),
        ),
        color = com.gamdo.app.data.preset.ColorParams(
            colorTemperature = 5200.0,
            exposureBias = 0.0,
            contrast = 0.0,
            saturation = 0.0,
            grain = 0.0,
            vignette = 0.0,
            blurStrength = 0.0,
            fade = 0.0,
        ),
    )

    @Test
    fun `idle maps to hidden`() {
        assertEquals(ReferenceSheetSection.HIDDEN, sheetSectionFor(ReferenceCreateState.Idle))
    }

    @Test
    fun `analyzing maps to the analyzing section`() {
        assertEquals(ReferenceSheetSection.ANALYZING, sheetSectionFor(ReferenceCreateState.Analyzing))
    }

    @Test
    fun `preview maps to the preview section`() {
        val resolution = com.gamdo.app.data.ReferenceResolution(
            contentHash = "hash",
            analysisVersion = 3,
            analysis = kotlinx.serialization.json.JsonObject(emptyMap()),
            targetComposition = kotlinx.serialization.json.JsonObject(emptyMap()),
            colorTarget = kotlinx.serialization.json.JsonObject(emptyMap()),
            compositionAvailable = true,
            colorAvailable = true,
            fromCache = false,
        )
        assertEquals(
            ReferenceSheetSection.PREVIEW,
            sheetSectionFor(ReferenceCreateState.Preview(resolution)),
        )
    }

    @Test
    fun `applied maps to the applied section`() {
        assertEquals(
            ReferenceSheetSection.APPLIED,
            sheetSectionFor(ReferenceCreateState.Applied(sampleStyle())),
        )
    }

    @Test
    fun `error maps to the error section regardless of retryability`() {
        assertEquals(ReferenceSheetSection.ERROR, sheetSectionFor(ReferenceCreateState.Error(retryable = true)))
        assertEquals(ReferenceSheetSection.ERROR, sheetSectionFor(ReferenceCreateState.Error(retryable = false)))
    }

    // ---- shouldShowReferenceOverlay ----
    //
    // Regression for the ghost-overlay bug: the camera overlay used to be keyed
    // on "was a photo ever picked this session", which a failed/abandoned flow
    // never cleared. A picked-but-never-applied photo is not a reference, so it
    // must never be able to make the overlay appear — no matter which sheet
    // state it was picked, analysed, previewed, or errored out in. `state` is
    // deliberately part of the signature: these tests assert the answer is the
    // *same* across every flow state for a given `hasActiveReference`, i.e. the
    // sheet state cannot influence the outcome at all.

    private fun previewResolution(compositionAvailable: Boolean = true) = com.gamdo.app.data.ReferenceResolution(
        contentHash = "hash",
        analysisVersion = 3,
        analysis = kotlinx.serialization.json.JsonObject(emptyMap()),
        targetComposition = kotlinx.serialization.json.JsonObject(emptyMap()),
        colorTarget = kotlinx.serialization.json.JsonObject(emptyMap()),
        compositionAvailable = compositionAvailable,
        colorAvailable = true,
        fromCache = false,
    )

    // AwaitingConsent is excluded — it carries an android.net.Uri, which cannot
    // be constructed on a plain JVM unit test (see this file's class KDoc).
    private val statesWithoutActiveReference = listOf(
        ReferenceCreateState.Idle,
        ReferenceCreateState.Analyzing,
        ReferenceCreateState.Preview(previewResolution()),
        ReferenceCreateState.Error(retryable = true),
        ReferenceCreateState.Error(retryable = false),
    )

    @Test
    fun `no active reference means no overlay, in every flow state including Error`() {
        for (state in statesWithoutActiveReference) {
            assertEquals(
                "state=$state must not show the overlay without an active reference",
                false,
                shouldShowReferenceOverlay(state, hasActiveReference = false),
            )
        }
    }

    @Test
    fun `idle with no active reference is no overlay — the exact ghost-overlay regression`() {
        // The bug: closing an errored/cancelled flow left the sheet back at
        // Idle while the picked photo kept overlaying the preview forever, with
        // no control anywhere to remove it. This is that exact case.
        assertEquals(false, shouldShowReferenceOverlay(ReferenceCreateState.Idle, hasActiveReference = false))
    }

    @Test
    fun `an active reference shows the overlay regardless of flow state`() {
        // Covers the "replace" case too: Analyzing/Preview/Error while a new
        // photo is being considered must keep showing the *old* active
        // reference's overlay, not hide it and not show the unconfirmed one.
        for (state in statesWithoutActiveReference) {
            assertEquals(
                "state=$state must show the overlay while a reference is active",
                true,
                shouldShowReferenceOverlay(state, hasActiveReference = true),
            )
        }
    }

    @Test
    fun `applied always has an active reference and always shows the overlay`() {
        assertEquals(
            true,
            shouldShowReferenceOverlay(ReferenceCreateState.Applied(sampleStyle()), hasActiveReference = true),
        )
    }
}
