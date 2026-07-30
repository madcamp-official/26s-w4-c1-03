package com.gamdo.app.ui.camera

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source guards for the redesign's structural properties — the ones whose *rule* is
 * already unit-tested in [CameraPanels] but whose **wiring** is not.
 *
 * The gap this closes is specific. `CameraPanelTest` proves that
 * `CameraPanels.filterPicked` returns the mode unchanged; it cannot prove that
 * `CameraScreen` routes the filter tap through it rather than writing
 * `storedMode = NONE` inline next to it. A tested function nobody calls is worth
 * nothing, and this project has that failure on record: `selectManualLayout` and
 * `availableManualLayouts` are complete, tested and have zero callers.
 *
 * `CameraScreen` is a `@Composable` and this module has no `androidTest`, no
 * Robolectric and no Compose UI test, so reading the source is the only gate. It goes
 * through [KotlinSourceProbe] rather than a local stripper — see that file for the two
 * bugs that made an earlier hand-rolled one pass while checking nothing.
 */
class CameraRedesignGuardTest {

    private val screen = File("src/main/java/com/gamdo/app/ui/camera/CameraScreen.kt")
    private val cameraPackage = File("src/main/java/com/gamdo/app/ui/camera")

    private fun code(): String = KotlinSourceProbe.codeLines(screen).joinToString("\n")

    @Test
    fun `the screen source this test guards actually exists`() {
        assertTrue(
            "CameraScreen.kt not found at ${screen.absolutePath} — if the file moved, " +
                "repoint this test rather than deleting it.",
            screen.isFile,
        )
        assertTrue(code().contains("fun CameraScreen("))
    }

    // ---- the rules must be routed through the tested functions -------------------

    @Test
    fun `the filter tap goes through the tested stay-open rule`() {
        assertTrue(
            "The sheet must stay open via CameraPanels.filterPicked, not by an inline " +
                "assignment beside it. Otherwise CameraPanelTest guards a function with " +
                "no callers — which this project already has two of.",
            code().contains("CameraPanels.filterPicked("),
        )
    }

    @Test
    fun `open and close go through the tested toggle`() {
        val source = code()
        assertTrue(source.contains("CameraPanels.toggled("))
        assertTrue(source.contains("CameraPanels.scrimTapped("))
    }

    @Test
    fun `the stored mode is read back through resolve`() {
        assertTrue(
            "rememberSaveable survives process death, so the debug-only settings mode " +
                "must be re-gated on read — see CameraPanels.resolve.",
            code().contains("CameraPanels.resolve("),
        )
    }

    /**
     * The dismiss layer is a `clickable` above the gesture surface. While it exists it
     * takes the DOWN, and pinch-to-zoom and tap-to-focus stop working **together and
     * silently** — the exact failure `CameraPreviewPane`'s KDoc documents, and the way
     * tap-to-focus was lost once already. It is correct while a sheet is up and a bug
     * at any other time, so it must be behind a condition rather than always mounted.
     */
    @Test
    fun `the sheet-dismiss layer only exists while a sheet is open`() {
        val lines = KotlinSourceProbe.codeLines(screen)
        val guard = lines.indexOfFirst { it.contains("if (dismissSheetOnTap)") }
        assertTrue(
            "the dismiss layer must be conditional — an unconditional clickable over " +
                "the preview kills pinch and tap-to-focus together",
            guard >= 0,
        )
        val block = KotlinSourceProbe.blockAt("if (dismissSheetOnTap)", lines)
        assertTrue(
            "the clickable must be *inside* that condition",
            lines.subList(block.first, block.last + 1).any { it.contains(".clickable(") },
        )
    }

    /**
     * The pencil's contract is 담당 B's, already written and until now with **zero
     * callers** — the same shape as `selectManualLayout`/`availableManualLayouts`, which
     * are complete, tested and unreachable. A finished contract nobody calls is the
     * failure mode this file exists to catch.
     */
    @Test
    fun `the pencil is wired to P2's polygon contract`() {
        val source = code()
        assertTrue(
            "the lasso must submit through CameraViewModel.rescanLayoutInPolygon",
            source.contains("rescanLayoutInPolygon"),
        )
        assertTrue(
            "cancelling must go through cancelPolygonLayoutSearch",
            source.contains("cancelPolygonLayoutSearch()"),
        )
        assertTrue(
            "whether to cancel is AreaSelectExit's decision, not an inline if",
            source.contains("AreaSelectExit.forExit("),
        )
    }

    /**
     * P1 must not hold a second copy of P2's area band (2%..80%). The thresholds live in
     * `ScenePolygonRegion.fromNormalized`, and a duplicate here would be the two copies
     * that drift — `rescanLayoutInPolygon` returning false is the whole interface.
     */
    @Test
    fun `the camera screen holds no copy of the polygon area thresholds`() {
        val offenders = cameraPackage.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                KotlinSourceProbe.codeLines(file).withIndex()
                    .filter { (_, line) -> line.contains("0.80f") || line.contains("0.02f") }
                    .map { (i, line) -> "${file.name}:${i + 1} ${line.trim()}" }
            }
            .toList()
        assertEquals(
            "the 2%..80% band is P2's (ScenePolygonRegion). P1 asks and reads the answer.",
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * §3.1's wiring. `availableManualLayouts` and `selectManualLayout` were complete,
     * tested, and had **zero callers** — the exact failure this file was created for.
     */
    @Test
    fun `the frame picker is wired to P2's manual layout contract`() {
        val source = code()
        assertTrue(
            "the list must come from CameraViewModel.availableManualLayouts",
            source.contains("availableManualLayouts"),
        )
        assertTrue(
            "selection must go through selectManualLayout",
            source.contains("selectManualLayout("),
        )
        assertTrue(
            "§3.1's 자동으로 돌아가기 exit is rescanLayout()",
            source.contains("rescanLayout()"),
        )
    }

    /**
     * The condition worth a structural guard: the frame button's lit state must come from
     * the **guide engine**, not from whether the sheet is open and not from what the sheet
     * last requested. That is how "선택 실패를 고정 성공으로 표시하지 않는다" holds without
     * anyone checking a boolean.
     */
    @Test
    fun `the frame button reads the engine, not the sheet`() {
        val lines = KotlinSourceProbe.codeLines(screen)
        val wiring = lines.withIndex()
            .filter { (_, line) -> line.contains("frameSheetActive =") }
            .map { (i, line) -> i to line }
        assertTrue("the frame button's state must be wired", wiring.isNotEmpty())
        for ((index, line) in wiring) {
            assertTrue(
                "line ${index + 1}: frameSheetActive must be derived from " +
                    "ManualFrameSelection (which reads layoutState), never from " +
                    "overlayMode == FRAME_SHEET. Got: ${line.trim()}",
                line.contains("ManualFrameSelection.frameButtonActive("),
            )
            assertFalse(
                "line ${index + 1}: an open sheet is not an active frame",
                line.contains("FRAME_SHEET"),
            )
        }
    }

    // ---- what the redesign removed stays removed --------------------------------

    @Test
    fun `the status pill is gone, not merely hidden`() {
        val offenders = cameraPackage.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { KotlinSourceProbe.codeLines(it).any { line -> line.contains("내 감도 적용 중") } }
            .map { it.name }
            .toList()
        assertEquals(
            "The redesign removed the status pill: mood state is the filter button's " +
                "dot now. Two controls saying the same thing is what was wrong with it.",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `the HUD chip is gone from the top bar`() {
        val lines = KotlinSourceProbe.codeLines(screen)
        val bar = KotlinSourceProbe.blockAt("private fun CameraTopBar(", lines)
        val body = lines.subList(bar.first, bar.last + 1).joinToString("\n")
        assertFalse(
            "the HUD toggle moved into the 설정 sheet; leaving a copy in the bar makes " +
                "two places to keep in step",
            body.contains("\"HUD\""),
        )
    }

    // ---- D2 still holds after the rebuild ---------------------------------------

    /**
     * D2-4: capture is manual only. The redesign rebuilt the shutter row, so it is
     * re-asserted here.
     *
     * **Structural, not proximity-based**, and the difference matters. A first draft
     * checked the six lines above each `onShutter =` for `LaunchedEffect`/`delay`, which
     * a realistic auto-capture would walk straight past: hoist the shutter body into a
     * local `val fire = { … }`, call it from the click *and* from an effect, and nothing
     * suspicious sits near either line. Asking instead where `controller.capture(`
     * **lives** catches that, because hoisting it moves it out of the block.
     */
    @Test
    fun `the only capture call is inside the shutter's click lambda`() {
        val lines = KotlinSourceProbe.codeLines(screen)
        val captures = lines.withIndex()
            .filter { (_, line) -> line.contains("controller.capture(") }
            .map { (i, _) -> i }
        assertEquals(
            "there must be exactly one capture call site in this screen",
            1,
            captures.size,
        )
        val clickBody = KotlinSourceProbe.blockAt("onShutter = {", lines)
        assertTrue(
            "D2-4: capture must be reachable from the shutter's onClick lambda and " +
                "nowhere else. Found `controller.capture(` at line ${captures[0] + 1}, " +
                "outside the onShutter block (lines ${clickBody.first + 1}..${clickBody.last + 1}). " +
                "Hoisting the shutter body so an effect can also call it is auto-capture.",
            captures[0] in clickBody,
        )
    }

    /**
     * D2-1 bans auto-capture "카운트다운 포함". A countdown needs a wait, and the only
     * way to wait is to suspend — so the absence of a timer in this file is a stronger
     * statement than the absence of the word "countdown".
     *
     * `delay` is the whole ban surface here: `CameraScreen` has no other legitimate use
     * for one. The teardown watchdog is a `Handler.postDelayed`, deliberately named in
     * the exclusion below rather than left to look like an oversight.
     */
    @Test
    fun `nothing on the camera screen waits before capturing`() {
        val offenders = KotlinSourceProbe.codeLines(screen)
            .withIndex()
            .filter { (_, line) -> Regex("""\bdelay\s*\(""").containsMatchIn(line) }
            .map { (i, line) -> "line ${i + 1}: ${line.trim()}" }
        assertEquals(
            "D2-1 bans auto-capture including a countdown, and a countdown needs a wait. " +
                "The one legitimate timer here is scheduleTeardownWatchdog's " +
                "Handler.postDelayed, which is not a `delay(` call.",
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * D2-1 bans a match gauge, and D2-5 bans `matchScore` from the shipped UI in any
     * form — including "colour intensity". The shutter now reacts to alignment, so the
     * thing to pin is that it reacts to a **Boolean**: no numeric score may reach it.
     */
    @Test
    fun `no progress or gauge widget appears on the camera screen`() {
        val banned = listOf("LinearProgressIndicator", "Slider(", "drawArc")
        val offenders = mutableListOf<String>()
        for (file in cameraPackage.walkTopDown().filter { it.isFile && it.extension == "kt" }) {
            for ((i, line) in KotlinSourceProbe.codeLines(file).withIndex()) {
                banned.filter { line.contains(it) }.forEach {
                    offenders += "${file.name}:${i + 1} $it"
                }
            }
        }
        assertEquals(
            "D2-1 bans 일치도 게이지·링·프로그레스 on this screen. A `drawArc` is the " +
                "usual first step of one. (CircularProgressIndicator in CameraOverlay is " +
                "the scene-search spinner, which is not a match gauge — it is not in this " +
                "list for that reason.)",
            emptyList<String>(),
            offenders,
        )
    }
}
