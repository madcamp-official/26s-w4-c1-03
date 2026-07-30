package com.gamdo.app.ui.reference

import com.gamdo.app.ui.camera.KotlinSourceProbe
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the one property that separates **hiding** the 내 감도 overlay from
 * **resetting** its 투명도.
 *
 * ## Why the distinction needs a guard of its own
 *
 * The 2026-07-31 owner fix makes the overlay come and go with the strip's style
 * selection ("필터를 내 감도가 아닌 다른 것으로 바꾸면 반투명 슬라이드바와
 * 레퍼런스 가이드도 당연히 없어져야 해"), and the obvious way to implement
 * "goes away" is to clear the state behind it. P2's §2 requirement forbids
 * exactly that — "투명도 UI는 `overlayAlpha` 단일 상태를 읽고 `onAlphaChange`만
 * 호출한다. 필터 선택 상태나 시트 닫힘으로 값을 다시 초기화하지 않는다" — so a
 * user who set 55%, shot a few frames on 깔끔한 소셜 and came back to 내 감도
 * must find 55%, not the 30% default.
 *
 * [shouldShowReferenceOverlay] cannot break this: it decides visibility and is
 * not given the alpha, and `ReferenceFlowDecisionsTest` covers its answers. What
 * is left to protect is the *state*, and that is one `var` in `GamdoNavHost` —
 * so what this file asserts is that nothing but the slider's own callback ever
 * assigns to it.
 *
 * ## Why source text
 *
 * `overlayAlpha` is `rememberSaveable` state inside a `@Composable`, and this
 * module has no `androidTest`, no Robolectric and no Compose UI test (see
 * `ReferenceCreateSheetDismissTest` for the same reasoning, and for why a
 * hand-rolled pure "model" of the state would be a hollow test — it would keep
 * passing after someone added the reset this file exists to catch). Counting the
 * writes in the source is a weaker check than driving the state, and it is the
 * only one available; reading goes through [KotlinSourceProbe] so a KDoc cannot
 * satisfy or violate an assertion about code.
 */
class ReferenceOverlayAlphaTest {

    private val navHost = File("src/main/java/com/gamdo/app/ui/navigation/GamdoNavHost.kt")
    private val cameraScreen = File("src/main/java/com/gamdo/app/ui/camera/CameraScreen.kt")
    private val mainSources = File("src/main/java/com/gamdo/app")

    /** `overlayAlpha` on the left of an `=`, i.e. a write rather than a read. */
    private val write = Regex("""\boverlayAlpha\s*=""")

    private fun codeOf(file: File): List<String> = KotlinSourceProbe.codeLines(file)

    @Test
    fun `the sources this test guards actually exist`() {
        assertTrue(
            "GamdoNavHost.kt not found at ${navHost.absolutePath} — if the file moved, " +
                "repoint this test rather than deleting it.",
            navHost.isFile,
        )
        assertTrue(cameraScreen.isFile)
        assertTrue(codeOf(navHost).any { it.contains("var overlayAlpha") })
    }

    @Test
    fun `the alpha is one saveable value, defaulted from the contract constant`() {
        val declaration = codeOf(navHost).single { it.contains("var overlayAlpha") }
        assertTrue(
            "the 투명도 must survive process death like every other in-session pick " +
                "on this screen. Was: ${declaration.trim()}",
            declaration.contains("rememberSaveable"),
        )
        assertTrue(
            "기본 30% is the contract's number (DEFAULT_REFERENCE_OVERLAY_ALPHA), not a " +
                "literal written here. Was: ${declaration.trim()}",
            declaration.contains("DEFAULT_REFERENCE_OVERLAY_ALPHA"),
        )
    }

    @Test
    fun `exactly one line in the whole app writes the overlay alpha`() {
        val offenders = mainSources.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                codeOf(file).withIndex()
                    .filter { (_, line) -> write.containsMatchIn(line) }
                    .map { (i, line) -> "${file.name}:${i + 1} ${line.trim()}" }
            }
            .toList()
        assertEquals(
            "P2 §2: 필터 선택 상태나 시트 닫힘으로 값을 다시 초기화하지 않는다. One write " +
                "means there is no second place that could reset it — a `overlayAlpha = " +
                "DEFAULT_REFERENCE_OVERLAY_ALPHA` next to a filter tap or a sheet close is " +
                "exactly the regression this counts.",
            1,
            offenders.size,
        )
        assertTrue(
            "the single write must be the slider's own callback, clamped to 0..60%. " +
                "Was: ${offenders.single()}",
            offenders.single().contains("clampReferenceOverlayAlpha("),
        )
    }

    @Test
    fun `the camera screen never sees the alpha at all`() {
        // The strongest form of "a filter tap cannot reset it": the screen that
        // owns the filter strip has no name for the value. Both halves of the
        // overlay reach it through slots the host fills, so the only code that
        // could reset it on a selection change is code that does not exist.
        val offenders = codeOf(cameraScreen).withIndex()
            .filter { (_, line) -> line.contains("overlayAlpha") }
            .map { (i, line) -> "line ${i + 1}: ${line.trim()}" }
        assertEquals(
            "the 투명도 state belongs to GamdoNavHost. If CameraScreen needs to read it, " +
                "the reset it is one edit away from is what this test is here to stop.",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `the slider reports through onAlphaChange and holds no copy of the value`() {
        // "투명도 UI는 overlayAlpha 단일 상태를 읽고 onAlphaChange만 호출한다."
        // A local `remember` of the position inside the control would be a second
        // copy of the value, and a second copy is a reset waiting to happen: it
        // would be rebuilt at the default every time the control is remounted,
        // which — now that switching filters unmounts it — is on every round trip.
        val strip = File("src/main/java/com/gamdo/app/ui/reference/ReferenceStrip.kt")
        val control = KotlinSourceProbe.blockAt("fun ReferenceOverlayAlphaControl(", codeOf(strip))
        val body = codeOf(strip).subList(control.first, control.last + 1)
        assertTrue(
            "the control must call onAlphaChange",
            body.any { it.contains("onAlphaChange(") },
        )
        val stateful = body.withIndex()
            .filter { (_, line) -> Regex("""\bremember(Saveable)?\s*[({]""").containsMatchIn(line) }
            .map { (i, line) -> "line ${control.first + i + 1}: ${line.trim()}" }
        assertEquals(
            "the 투명도 control must be stateless — one value, held by the host.",
            emptyList<String>(),
            stateful,
        )
    }
}
