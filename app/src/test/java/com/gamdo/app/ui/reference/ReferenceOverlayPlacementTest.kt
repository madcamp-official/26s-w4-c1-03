package com.gamdo.app.ui.reference

import com.gamdo.app.ui.camera.KotlinSourceProbe
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins where the 내 감도 투명도 slider is mounted. Two defects live at that one
 * decision, and they pull in opposite directions.
 *
 * ## Defect A — inside the preview box, too low in the stack (2026-07-31, 사용 불가)
 *
 * The slider sat at the preview box's `BottomStart`, 12dp up, drawn right after
 * the reference photo. Two layers the pane mounts above it take it away, and
 * neither can move:
 *
 *  - while any sheet is open, `CameraPreviewPane`'s topmost child is a
 *    transparent full-size `clickable(onDismissSheet)`. Compose hit-tests
 *    siblings in reverse z-order and stops at the first pointer-input node, so a
 *    drag on the slider's thumb closed the sheet instead of moving the slider.
 *  - the aspect mask's letterbox bars are opaque `Ink950` and are drawn over the
 *    reference layer so nothing spills onto them. At 4:5 on a phone pane those
 *    bars are tens of dp tall and swallow a control 12dp off the pane's bottom.
 *
 * ## Defect B — outside the preview box entirely
 *
 * The first fix for A moved the slider into the screen's Column, as a sibling of
 * the pane. That does fix A, and it silently changed the **saved photo**: the
 * pane takes `weight(1f)`, its ratio is the CameraX viewport, and the viewport
 * crop runs before the aspect crop (`captureGeometryFor`). Measured on device at
 * 4:5 — `pane=0.6475 → 2610×3263`, `pane=0.8163 → 2189×2736`. A sheet pays that
 * while the user is looking at it; a row that stays for as long as 내 감도 is
 * selected changes the field of view of every photo taken with it.
 *
 * So the mount has to satisfy both at once: **inside the pane** (so it takes no
 * layout height from it) and **last** (above the mask and above the dismiss
 * layer), inset to the visible window. That is what this file holds.
 *
 * ## Why source text
 *
 * Layer order, hit testing and layout height are all Compose facts this module
 * cannot evaluate — no `androidTest`, no Robolectric, no Compose UI test. Reading
 * goes through [KotlinSourceProbe]; see that file for the two stripper bugs that
 * made an earlier guard pass while checking nothing.
 */
class ReferenceOverlayPlacementTest {

    private val screen = File("src/main/java/com/gamdo/app/ui/camera/CameraScreen.kt")
    private val strip = File("src/main/java/com/gamdo/app/ui/reference/ReferenceStrip.kt")

    private fun screenLines(): List<String> = KotlinSourceProbe.codeLines(screen)
    private fun stripLines(): List<String> = KotlinSourceProbe.codeLines(strip)
    private fun screenCode(): String = screenLines().joinToString("\n")

    /**
     * Character range of a **call**'s argument list — from its `(` to the matching
     * `)`, skipping the declaration of the same name.
     *
     * Parentheses rather than braces, because what is being asked is "is this text
     * an *argument* of that call", and [KotlinSourceProbe.blockAt] answers about
     * braces: pointed at a call it closes on the first lambda argument, and pointed
     * at `fun CameraScreen(` it closes on a `= {}` parameter default. Comments are
     * already blanked, so only code parentheses are counted.
     */
    private fun callArguments(code: String, marker: String): IntRange {
        var from = 0
        while (true) {
            val start = code.indexOf(marker, from)
            assertTrue("call site not found: $marker", start >= 0)
            val declaration = code.lastIndexOf("fun ", start).let { it >= 0 && start - it <= 12 }
            if (declaration) {
                from = start + marker.length
                continue
            }
            var depth = 0
            var i = start + marker.length - 1
            while (i < code.length) {
                if (code[i] == '(') depth++
                if (code[i] == ')') {
                    depth--
                    if (depth == 0) return start..i
                }
                i++
            }
            error("unbalanced parentheses after: $marker")
        }
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

    // ---- defect B: the control must not take height from the preview pane ------

    @Test
    fun `the slider is wired into the preview pane, never mounted beside it`() {
        val code = screenCode()
        val pane = callArguments(code, "CameraPreviewPane(")
        val mounts = Regex("""referenceOverlayControl\(""").findAll(code).map { it.range.first }.toList()
        assertTrue(
            "the 투명도 slot is not invoked anywhere in CameraScreen. If it was " +
                "renamed, repoint this test; if it was removed, read this class's KDoc.",
            mounts.isNotEmpty(),
        )
        val outside = mounts.filterNot { it in pane }.map { code.describeOffset(it) }
        assertEquals(
            "every mount must be an argument of the CameraPreviewPane call. A mount " +
                "outside it is a statement in the screen's Column, which takes layout " +
                "height from the weight(1f) pane — and this screen's pane ratio is the " +
                "CameraX viewport, so it narrows every saved photo. See the class KDoc " +
                "for the device numbers.",
            emptyList<String>(),
            outside,
        )
    }

    @Test
    fun `the slot is mounted exactly once`() {
        val mounts = Regex("""referenceOverlayControl\(""").findAll(screenCode()).count()
        assertEquals(
            "one mount point. Two would put the same slider in two places, and only " +
                "one of them can be the one inside the pane.",
            1,
            mounts,
        )
    }

    // ---- defect A: it must be the pane's last child, inset to the window -------

    @Test
    fun `the slider is drawn after the sheet-dismiss layer`() {
        val lines = screenLines()
        val dismiss = KotlinSourceProbe.blockAt("if (dismissSheetOnTap)", lines)
        val control = lines.indexOfFirst { it.contains("referenceControl()") }
        assertTrue("the pane must invoke its referenceControl slot", control >= 0)
        assertTrue(
            "the slider must come after the dismiss layer (control at line " +
                "${control + 1}, dismiss block ends at line ${dismiss.last + 1}). Before " +
                "it, the dismiss clickable takes the DOWN and dragging the slider closes " +
                "the sheet instead of moving it — the original 사용 불가 report.",
            control > dismiss.last,
        )
    }

    @Test
    fun `the slider is inset to the aspect window rather than the pane`() {
        // The mask's own bars, from `previewWindowOf`. Anchored to the pane instead,
        // the control sits behind an opaque letterbox bar at 4:5.
        val lines = screenLines()
        val control = lines.indexOfFirst { it.contains("referenceControl()") }
        assertTrue(control >= 0)
        val dismiss = KotlinSourceProbe.blockAt("if (dismissSheetOnTap)", lines)
        val mount = lines.subList(dismiss.last + 1, minOf(control + 2, lines.size)).joinToString("\n")
        assertTrue(
            "the mount must apply the mask's insets (barWidth/barHeight), or the " +
                "control is positioned against the pane and lands on a bar:\n$mount",
            mount.contains("barWidth") && mount.contains("barHeight"),
        )
    }

    // ---- what the two composables may be --------------------------------------

    @Test
    fun `the photo layer no longer carries the slider`() {
        val lines = stripLines()
        val layer = KotlinSourceProbe.blockAt("fun ReferenceOverlayLayer(", lines)
        val body = lines.subList(layer.first, layer.last + 1)
        assertTrue(
            "ReferenceOverlayLayer is drawn under the mask and under the dismiss " +
                "layer, so a Slider in it is the original defect exactly.",
            body.none { it.contains("Slider(") },
        )
        assertTrue(
            "and it must not take the change callback either — a layer that cannot " +
                "change the value cannot grow a control for it by accident",
            body.none { it.contains("onAlphaChange") },
        )
    }

    @Test
    fun `the control stays small and does not position itself`() {
        // Now that it is mounted *over* the gesture surface, its size is the size of
        // the hole it punches in pinch / tap-to-focus / 올가미. P2: "작은 실제
        // 컨트롤만 소비해야 카메라 제스처가 유지된다". A fillMaxWidth here would be
        // the full-screen pointer handler that constraint forbids, arriving as a
        // layout modifier rather than as a `pointerInput`.
        //
        // `align` is banned for a different reason: it only compiles inside a
        // BoxScope, and where the control sits is the pane's business — only the
        // pane knows where the visible window is.
        val lines = stripLines()
        val control = KotlinSourceProbe.blockAt("fun ReferenceOverlayAlphaControl(", lines)
        val offenders = lines.subList(control.first, control.last + 1).withIndex()
            .filter { (_, line) ->
                line.contains("fillMaxWidth") || line.contains("fillMaxSize") || line.contains(".align(")
            }
            .map { (i, line) -> "line ${control.first + i + 1}: ${line.trim()}" }
        assertEquals(
            "the 투명도 control must be content-sized and unpositioned",
            emptyList<String>(),
            offenders,
        )
    }
}

/** `line N: text` for a character offset into comment-stripped source. */
private fun String.describeOffset(offset: Int): String {
    val line = substring(0, offset).count { it == '\n' } + 1
    val start = lastIndexOf('\n', offset - 1) + 1
    val end = indexOf('\n', offset).let { if (it < 0) length else it }
    return "line $line: ${substring(start, end).trim()}"
}
