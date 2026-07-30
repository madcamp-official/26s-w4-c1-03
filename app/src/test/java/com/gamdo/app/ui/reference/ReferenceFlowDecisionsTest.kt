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
    //
    // `referenceSelected` is the second half, added 2026-07-31 for the owner's
    // "필터를 내 감도가 아닌 다른 것으로 바꾸면 반투명 슬라이드바와 레퍼런스
    // 가이드도 당연히 없어져야 해". The tests that predate it now pass `true`
    // there, so they keep asserting exactly what they always did — what an
    // *existing* reference does — and the new ones below own the other axis.

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
                shouldShowReferenceOverlay(state, hasActiveReference = false, referenceSelected = true),
            )
        }
    }

    @Test
    fun `idle with no active reference is no overlay — the exact ghost-overlay regression`() {
        // The bug: closing an errored/cancelled flow left the sheet back at
        // Idle while the picked photo kept overlaying the preview forever, with
        // no control anywhere to remove it. This is that exact case.
        assertEquals(
            false,
            shouldShowReferenceOverlay(
                ReferenceCreateState.Idle,
                hasActiveReference = false,
                referenceSelected = true,
            ),
        )
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
                shouldShowReferenceOverlay(state, hasActiveReference = true, referenceSelected = true),
            )
        }
    }

    @Test
    fun `applied always has an active reference and always shows the overlay`() {
        assertEquals(
            true,
            shouldShowReferenceOverlay(
                ReferenceCreateState.Applied(sampleStyle()),
                hasActiveReference = true,
                referenceSelected = true,
            ),
        )
    }

    // ---- the 2026-07-31 owner fix: the overlay follows the *selected* style ----

    @Test
    fun `switching the strip to a preset hides the overlay even though the 감도 still exists`() {
        // The report, exactly: make a 내 감도, then tap 깔끔한 소셜 or 원본. The
        // reference is still stored and its slot is still on the strip
        // (hasActiveReference stays true) — it is simply not the style being
        // shot with any more, so the translucent photo and its 투명도 slider
        // have nothing to be about.
        assertEquals(
            false,
            shouldShowReferenceOverlay(
                ReferenceCreateState.Idle,
                hasActiveReference = true,
                referenceSelected = false,
            ),
        )
    }

    @Test
    fun `an unselected 감도 stays hidden in every flow state`() {
        // Same property as the `state`-invariance above, on the new axis: no
        // sheet state may sneak the overlay back on for a 감도 the strip is not
        // using. In particular a replace-in-progress (Analyzing/Preview over an
        // existing reference) must not re-show it.
        for (state in statesWithoutActiveReference + ReferenceCreateState.Applied(sampleStyle())) {
            assertEquals(
                "state=$state must not show the overlay for an unselected 감도",
                false,
                shouldShowReferenceOverlay(state, hasActiveReference = true, referenceSelected = false),
            )
        }
    }

    @Test
    fun `selecting 내 감도 again brings the overlay back`() {
        // The other direction of the same switch, which is what makes the
        // 투명도 값 requirement meaningful: coming back has to show the overlay,
        // so there is something for the preserved alpha to apply to. That the
        // *value* survives the round trip is `ReferenceOverlayAlphaTest`'s —
        // this function cannot reach the alpha at all, which is the point.
        assertEquals(
            true,
            shouldShowReferenceOverlay(
                ReferenceCreateState.Idle,
                hasActiveReference = true,
                referenceSelected = true,
            ),
        )
    }

    // ---- shouldAutoSelectReference ----
    //
    // The companion to the gate above. Making the overlay follow the selection
    // opens a hole at the moment 내 감도 만들기 finishes — the new strip slot is
    // not selected yet — and this closes it.
    //
    // The axis that matters is *narrowness*. Too wide and it fights the user:
    // anything true while the strip is reachable would put 내 감도 back the
    // frame after they picked a preset, and the owner's fix could never hold.
    // Too narrow and the flow ends with nothing visibly applied. Only Applied
    // is both, and these tests are that claim, one state at a time.

    @Test
    fun `finishing the flow selects the 감도`() {
        assertEquals(true, shouldAutoSelectReference(ReferenceCreateState.Applied(sampleStyle())))
    }

    @Test
    fun `no other flow state selects anything`() {
        // Idle is the load-bearing one. It is where the controller sits on a
        // launch that restored a 감도 from Room, and where it returns the moment
        // the create sheet is dismissed — i.e. every moment the filter strip is
        // reachable. If Idle selected, picking 깔끔한 소셜 would be undone on the
        // next frame, and a launch would override the onboarding style (§6-2)
        // with a 감도 the user made days ago.
        for (state in statesWithoutActiveReference) {
            assertEquals(
                "state=$state must not auto-select",
                false,
                shouldAutoSelectReference(state),
            )
        }
    }

    @Test
    fun `re-making a 감도 from the same photo still selects it`() {
        // The regression this replaced: the trigger used to be the picked photo's
        // Uri, so making a 감도 again from the *same* photo — or deleting one and
        // making it again — produced the same key and the effect never re-ran.
        // The user watched the upload, the analysis and 적용됐어요, and nothing
        // changed on the strip. The flow passes through Analyzing on the way, so
        // keying on the state sees every completed run whatever the photo was.
        val sameStyleTwice = ReferenceCreateState.Applied(sampleStyle())
        assertEquals(true, shouldAutoSelectReference(sameStyleTwice))
        assertEquals(false, shouldAutoSelectReference(ReferenceCreateState.Analyzing))
        assertEquals(true, shouldAutoSelectReference(ReferenceCreateState.Applied(sampleStyle())))
    }

    @Test
    fun `selection alone is not enough — a deleted 감도 stays hidden`() {
        // `referenceSelected` is camera-local `rememberSaveable` state and
        // `hasActiveReference` is the host's. They can disagree for a frame
        // after a delete, and the answer must be "no overlay" both times.
        assertEquals(
            false,
            shouldShowReferenceOverlay(
                ReferenceCreateState.Idle,
                hasActiveReference = false,
                referenceSelected = true,
            ),
        )
    }
}
