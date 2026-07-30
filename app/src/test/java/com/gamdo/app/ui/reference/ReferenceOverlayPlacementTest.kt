package com.gamdo.app.ui.reference

import com.gamdo.app.ui.camera.KotlinSourceProbe
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins where the 내 감도 투명도 slider is mounted, which is the whole of the
 * 2026-07-31 사용 불가 fix.
 *
 * ## The defect
 *
 * The slider lived at the preview box's `BottomStart`, 12dp up. Two layers the
 * preview box mounts *above* the reference layer take it away, and neither is
 * removable:
 *
 *  - while any sheet is open, `CameraPreviewPane`'s topmost child is a
 *    transparent full-size `clickable(onDismissSheet)`, so Compose's hit test
 *    stops there and a drag on the slider's thumb closed the sheet instead of
 *    moving the slider. `CameraRedesignGuardTest` guards that layer's existence
 *    for its own good reasons — it is not going away.
 *  - the aspect mask's letterbox bars are opaque `Ink950` and are drawn over the
 *    reference layer so nothing spills onto them. At 4:5 on a phone pane those
 *    bars are tens of dp tall and swallow a control sitting 12dp off the bottom.
 *
 * So the fix is not a nicer offset inside the box — it is being outside the box,
 * as a sibling of the preview pane in the screen's Column. That is the same
 * structural argument the sheet slot already makes about the shutter row: a
 * sibling earlier in the Column cannot be covered by one later in it, whatever
 * height either turns out to have.
 *
 * ## Why source text
 *
 * The real property is a Compose hit-testing and layer-order fact and cannot be
 * evaluated here — no `androidTest`, no Robolectric, no Compose UI test in this
 * module. What is checkable is that the control is still a Column sibling rather
 * than back inside the preview, which is the single edit that reintroduces the
 * bug. Reading goes through [KotlinSourceProbe]; see that file for the two
 * stripper bugs that made an earlier guard pass while checking nothing.
 */
class ReferenceOverlayPlacementTest {

    private val screen = File("src/main/java/com/gamdo/app/ui/camera/CameraScreen.kt")
    private val strip = File("src/main/java/com/gamdo/app/ui/reference/ReferenceStrip.kt")

    private fun screenLines(): List<String> = KotlinSourceProbe.codeLines(screen)
    private fun stripLines(): List<String> = KotlinSourceProbe.codeLines(strip)

    private fun indentOf(line: String): Int = line.takeWhile { it == ' ' }.length

    /** The `CameraPreviewPane(` **call**, not the private function that declares it. */
    private fun paneCallIndex(): Int {
        val index = screenLines().indexOfFirst {
            it.contains("CameraPreviewPane(") && !it.contains("fun ")
        }
        assertTrue("could not find the CameraPreviewPane call site", index >= 0)
        return index
    }

    private fun controlCallIndex(): Int {
        val index = screenLines().indexOfFirst { it.contains("referenceOverlayControl(") }
        assertTrue(
            "the 투명도 slot is not called anywhere in CameraScreen. If it was renamed, " +
                "repoint this test; if it was folded back into the preview box, read this " +
                "class's KDoc first.",
            index >= 0,
        )
        return index
    }

    @Test
    fun `the sources this test guards actually exist`() {
        assertTrue(
            "CameraScreen.kt not found at ${screen.absolutePath} — if the file moved, " +
                "repoint this test rather than deleting it.",
            screen.isFile,
        )
        assertTrue(strip.isFile)
        assertTrue(screenLines().any { it.contains("fun CameraScreen(") })
    }

    @Test
    fun `the slider is a sibling of the preview pane, not a child of it`() {
        val lines = screenLines()
        val pane = paneCallIndex()
        val control = controlCallIndex()
        assertTrue(
            "the 투명도 control must be mounted after the preview pane in the screen's " +
                "Column (line ${control + 1} vs pane at line ${pane + 1})",
            control > pane,
        )
        assertEquals(
            "the control must sit at the same nesting level as CameraPreviewPane, i.e. be " +
                "a statement in the screen's Column. A deeper indent means it went back " +
                "inside the preview box — where the sheet-dismiss layer eats its touches " +
                "and the aspect mask hides it. Was: ${lines[control].trim()}",
            indentOf(lines[pane]),
            indentOf(lines[control]),
        )
    }

    @Test
    fun `the slider is mounted above the sheet slot`() {
        // The sheet cannot cover what the Column places before it. This is the
        // half of the fix that makes "가려진다" structurally impossible rather
        // than a matter of how tall the sheet happens to be.
        val lines = screenLines()
        val firstSheet = lines.indexOfFirst { it.contains("CameraSheetSlot(") && !it.contains("fun ") }
        assertTrue("could not find a CameraSheetSlot call site", firstSheet >= 0)
        assertTrue(
            "the 투명도 control must come before the first sheet slot (control at line " +
                "${controlCallIndex() + 1}, sheet at line ${firstSheet + 1})",
            controlCallIndex() < firstSheet,
        )
    }

    @Test
    fun `the slot is called exactly once`() {
        val calls = screenLines().count { it.contains("referenceOverlayControl(") }
        assertEquals(
            "one mount point. Two would put the same slider in two places, and only one " +
                "of them can be the one outside the preview box.",
            1,
            calls,
        )
    }

    @Test
    fun `the photo layer no longer carries the slider`() {
        val lines = stripLines()
        val layer = KotlinSourceProbe.blockAt("fun ReferenceOverlayLayer(", lines)
        val body = lines.subList(layer.first, layer.last + 1)
        assertTrue(
            "ReferenceOverlayLayer mounts inside the preview box, so a Slider in it is " +
                "the original defect. The control belongs in ReferenceOverlayAlphaControl.",
            body.none { it.contains("Slider(") },
        )
        assertTrue(
            "and it must not take the change callback either — a layer that cannot " +
                "change the value cannot grow a control for it by accident",
            body.none { it.contains("onAlphaChange") },
        )
    }

    @Test
    fun `the control positions itself in flow, never against a Box`() {
        // `align(Alignment.…)` only compiles inside a BoxScope, so its absence is
        // evidence the control is not written to be dropped back into the preview
        // box — which is what "sibling in a Column" means in Compose terms.
        val lines = stripLines()
        val control = KotlinSourceProbe.blockAt("fun ReferenceOverlayAlphaControl(", lines)
        val offenders = lines.subList(control.first, control.last + 1).withIndex()
            .filter { (_, line) -> line.contains(".align(") }
            .map { (i, line) -> "line ${control.first + i + 1}: ${line.trim()}" }
        assertEquals(
            "a BoxScope alignment here means the control is being positioned against " +
                "the preview again",
            emptyList<String>(),
            offenders,
        )
    }
}
