package com.gamdo.app.ui.camera

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-C1: the camera screen and the debug HUD are separate things.
 *
 * The reported defect was not "the HUD exists" — it is supposed to, for
 * development — but that **it was on by default**. After clearing app data, a
 * fresh debug install opened the camera with raw object counts, analysis fps, IoU,
 * matchScore and the latched template id already on screen. Two halves to that:
 *
 * 1. the `demo` build type (`isDebuggable = false`, so `BuildConfig.DEBUG` is
 *    false) compiles the whole branch out. Owner-verified on device 2026-07-30.
 * 2. a debug build must still *start* hidden. That is this file's subject.
 *
 * The behaviour lives in [DebugHudGate] so it can be executed here; the source
 * assertions below exist because a `@Composable` cannot be, and the regression
 * happened inside one.
 */
class DebugHudGateTest {

    // ---- behaviour -------------------------------------------------------

    @Test
    fun `the hud starts hidden in a debug build`() {
        assertFalse(
            "P1-C1: a fresh debug install must open the camera with no HUD. " +
                "The defect was `mutableStateOf(BuildConfig.DEBUG)`.",
            DebugHudGate.initialVisible(isDebugBuild = true),
        )
    }

    @Test
    fun `the hud starts hidden in a product build`() {
        assertFalse(DebugHudGate.initialVisible(isDebugBuild = false))
    }

    @Test
    fun `a debug build shows the hud only after the toggle`() {
        assertFalse(DebugHudGate.visible(isDebugBuild = true, toggledOn = false))
        assertTrue(DebugHudGate.visible(isDebugBuild = true, toggledOn = true))
    }

    /**
     * `showHud` is `rememberSaveable`, so its value can arrive from a bundle
     * written by a different build of the same app. The build-type term has to
     * survive that, which is why [DebugHudGate.visible] takes both.
     */
    @Test
    fun `a restored toggle cannot surface the hud in a product build`() {
        assertFalse(
            "D2-5: matchScore must not reach the shipped UI in any form, including " +
                "via a restored saved-state bundle.",
            DebugHudGate.visible(isDebugBuild = false, toggledOn = true),
        )
    }

    @Test
    fun `the hud does not exist in a product build`() {
        assertFalse(DebugHudGate.availableIn(isDebugBuild = false))
        assertTrue(DebugHudGate.availableIn(isDebugBuild = true))
    }

    // ---- wiring ----------------------------------------------------------
    //
    // A correct gate that nobody calls is the same screen as no gate. These read
    // the source because CameraScreen is a @Composable: no androidTest source set,
    // no Robolectric, so it cannot be rendered or measured on the JVM.

    private val screenSource = File("src/main/java/com/gamdo/app/ui/camera/CameraScreen.kt")

    /** Strips comments so the assertions read code, not the prose explaining it. */
    private fun code(): String =
        screenSource.readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .lines().joinToString("\n") { it.substringBefore("//") }

    @Test
    fun `the screen source this test guards actually exists`() {
        assertTrue(
            "CameraScreen.kt not found at ${screenSource.absolutePath} — if the file " +
                "moved, repoint this test rather than deleting it.",
            screenSource.isFile,
        )
        assertTrue("this should be the camera host", code().contains("fun CameraScreen("))
    }

    @Test
    fun `the toggle's initial value is not derived from the build type`() {
        val line = code().lines().firstOrNull { it.contains("var showHud") }
        assertNotNull("`var showHud` disappeared — repoint this test, do not delete it.", line)
        assertTrue(
            "P1-C1: `showHud` must start from DebugHudGate.initialVisible(), which is " +
                "always false. Found: ${line!!.trim()}",
            line.contains("DebugHudGate.initialVisible("),
        )
        assertFalse(
            "P1-C1: the HUD default must not read the build type. Found: ${line.trim()}",
            line.contains("BuildConfig.DEBUG") && !line.contains("DebugHudGate."),
        )
    }

    @Test
    fun `no camera state defaults to the build type`() {
        val offenders = code().lines()
            .withIndex()
            .filter { (_, line) -> line.contains("mutableStateOf(BuildConfig.DEBUG)") }
            .map { (i, line) -> "line ${i + 1}: ${line.trim()}" }
        assertEquals(
            "P1-C1: nothing on the camera screen may switch itself on because this " +
                "happens to be a debug build.\n" + offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * The read-outs are reachable from exactly one place, and that place is gated.
     *
     * Stated as containment rather than "every call site has an `if` above it"
     * because containment is what actually holds: a new badge added to [CameraHud]
     * inherits the gate, and a new badge added anywhere else fails this test. The
     * list is the debug vocabulary P1-C1 names — 원시 객체 수, FPS, IoU, match, fixed.
     */
    @Test
    fun `debug read-outs are reachable only from inside CameraHud`() {
        val lines = code().lines()
        val body = bodyLineRangeOf("private fun CameraHud(", lines)
        val offenders = mutableListOf<String>()
        for (name in READOUTS) {
            val call = Regex("""(^|[^A-Za-z0-9_.])$name\s*\(""")
            lines.forEachIndexed { i, line ->
                val isDeclaration = line.contains("fun $name(")
                if (!isDeclaration && call.containsMatchIn(line) && i !in body) {
                    offenders += "line ${i + 1}: ${line.trim()}"
                }
            }
        }
        assertEquals(
            "P1-C1: 원시 객체 수·FPS·IoU·match·fixed must not be rendered outside the " +
                "gated CameraHud block. Add the read-out to CameraHud instead of to " +
                "the product screen.\n" + offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `the only CameraHud call site is behind the gate`() {
        val lines = code().lines()
        val callSites = lines.withIndex().filter { (_, line) ->
            line.contains("CameraHud(") && !line.contains("fun CameraHud(")
        }
        assertEquals("expected exactly one CameraHud call site", 1, callSites.size)
        val (index, _) = callSites.single()
        val window = lines.subList((index - 3).coerceAtLeast(0), index).joinToString("\n")
        assertTrue(
            "P1-C1: CameraHud must be mounted behind DebugHudGate.visible(...). " +
                "Preceding lines were:\n$window",
            window.contains("DebugHudGate.visible("),
        )
    }

    /**
     * Brace-matches a declaration's body and returns the line indices it spans.
     *
     * Load-bearing for [debug read-outs are reachable only from inside CameraHud],
     * so [the body matcher finds a function's extent] pins it separately: a matcher
     * that returned the whole file would make that test pass unconditionally.
     */
    private fun bodyLineRangeOf(declaration: String, lines: List<String>): IntRange {
        val start = lines.indexOfFirst { it.contains(declaration) }
        require(start >= 0) { "declaration not found: $declaration" }
        var depth = 0
        var opened = false
        for (i in start until lines.size) {
            for (ch in lines[i]) {
                if (ch == '{') { depth++; opened = true }
                if (ch == '}') depth--
            }
            if (opened && depth == 0) return start..i
        }
        error("unbalanced braces after: $declaration")
    }

    @Test
    fun `the body matcher finds a function's extent`() {
        val lines = """
            fun before() { }
            private fun Target(
                a: Int,
            ) {
                if (a > 0) {
                    Inner()
                }
            }
            fun after() { Inner() }
        """.trimIndent().lines()
        val range = bodyLineRangeOf("private fun Target(", lines)
        assertEquals(1..7, range)
        assertFalse("the trailing call must fall outside", 8 in range)
    }

    private companion object {
        val READOUTS = listOf(
            "DebugHud",
            "PreviewFpsHud",
            "DetectionBadge",
            "TiltBadge",
            "GuideDebugBadge",
        )
    }
}
