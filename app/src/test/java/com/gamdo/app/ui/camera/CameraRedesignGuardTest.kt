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
     * D2-4: capture is manual only. The redesign rebuilt the shutter row, so this is
     * re-asserted here — an `onFrame` / `LaunchedEffect` / `collect` reaching the
     * shutter would be auto-capture however it was introduced.
     */
    @Test
    fun `the shutter is reachable from a click and nothing else`() {
        val lines = KotlinSourceProbe.codeLines(screen)
        val callers = lines.withIndex()
            .filter { (_, line) -> line.contains("onShutter") }
            .map { (i, line) -> "line ${i + 1}: ${line.trim()}" }
        assertTrue("the shutter must still be wired", callers.isNotEmpty())
        val forbidden = listOf("LaunchedEffect", "collect {", "delay(", "onFrame")
        for ((index, line) in lines.withIndex()) {
            if (!line.contains("onShutter =") && !line.contains("onClick = onShutter")) continue
            val window = lines.subList((index - 6).coerceAtLeast(0), index)
            for (marker in forbidden) {
                assertFalse(
                    "D2-4: `takePicture` runs from a click lambda only. Line ${index + 1} " +
                        "sits under `$marker`, which would be auto-capture.",
                    window.any { it.contains(marker) },
                )
            }
        }
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
