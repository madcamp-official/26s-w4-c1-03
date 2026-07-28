package com.gamdo.app.ui.camera

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the two D2 properties of the camera overlay that have already regressed once.
 *
 * D2 (AGENTS.md, 재론 불가 by 규칙 3) says the guide is **visual only**: no text
 * instructions, no direction arrows, no match gauge, no auto-capture. It also says
 * fixed-layout slots must not show occupancy state.
 *
 * Both were violated in shipped code. `CameraOverlay` rendered
 * `Text("피사체를 화면에 보여주세요")` and `Text("피사체를 잠시 유지해주세요")` at
 * TopCenter with no debug gate, and mapped slot fills to three different colours by
 * FILLED / DETECTING / EMPTY. The file's own KDoc claimed "there is still no arrow,
 * match gauge, or auto-capture" four lines above the banned copy — **prose did not
 * hold the line, so a test does.**
 *
 * ## Why source text
 *
 * `CameraOverlay` is a `@Composable` drawing into a `Canvas`. With no `androidTest`
 * source set and no Robolectric on the classpath, it cannot be rendered or measured
 * on the JVM at all. Reading the source is the only gate available here, and the two
 * properties below are cheap to state that way: a banned call and a banned symbol.
 *
 * A failure here is not "reword the string". It means someone is putting an
 * instruction back on the camera screen, and D2 requires an explicit owner
 * reversal before that is allowed.
 */
class CameraOverlayD2Test {

    private val overlaySource = File("src/main/java/com/gamdo/app/ui/camera/CameraOverlay.kt")

    /** Strips comments so the assertions read code, not the prose explaining it. */
    private fun code(): String =
        overlaySource.readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .lines().joinToString("\n") { it.substringBefore("//") }

    @Test
    fun `the overlay source this test guards actually exists`() {
        assertTrue(
            "CameraOverlay.kt not found at ${overlaySource.absolutePath} — if the file " +
                "moved, repoint this test rather than deleting it.",
            overlaySource.isFile,
        )
        assertTrue("this should be the Canvas overlay", code().contains("fun CameraOverlay("))
    }

    @Test
    fun `the camera overlay renders no text`() {
        val offenders = code().lines()
            .withIndex()
            .filter { (_, line) -> Regex("""\bText\s*\(""").containsMatchIn(line) }
            .map { (i, line) -> "line ${i + 1}: ${line.trim()}" }

        assertEquals(
            "D2 bans instruction copy on the camera screen and 규칙 3 makes D2 재론 불가. " +
                "Rendering text here needs an explicit owner reversal first, not a test edit.\n" +
                offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * Note the symbols here are the **occupancy** ones only.
     *
     * A first draft of this test also banned the bare words `FILLED` and
     * `DETECTING`, and it failed on `LayoutGuideLevel.DETECTING` — which is a
     * different axis entirely. That enum grades how much the detector trusts the
     * subject *outline*, and the overlay uses it to fade the outline in. It says
     * nothing about whether the user has succeeded, so it is not the gauge D2
     * bans. Matching on bare words instead of the actual symbols would have made
     * this test forbid a legitimate feature.
     */
    @Test
    fun `fixed-layout slots carry no occupancy state`() {
        val source = code()
        for (symbol in listOf("SlotMatchStatus", "SlotMatch", "allRequiredFilled")) {
            assertFalse(
                "D2: slot fills must not vary by occupancy. Found `$symbol` in CameraOverlay — " +
                    "a slot that changes colour when filled is a match gauge in another shape.",
                source.contains(symbol),
            )
        }
    }

    /**
     * The style-preset guide must actually be drawn.
     *
     * 부록 A names "목표 프레임·실루엣·수평선 오버레이" as one of the things this
     * project keeps to the end, and §3-2's completion criterion is stated in that
     * exact vocabulary — a bracket that turns sage when the subject is inside it.
     *
     * This test exists because the block was once **commented out wholesale** on a
     * parallel branch, and the merge that brought it in was green. Nothing else
     * noticed: the code still compiled, every other test still passed, and the
     * camera screen simply drew fewer marks.
     *
     * Note it asserts on the **raw source**, not on [code]. The earlier version of
     * this class checked a stripped copy, and stripping is precisely what hid the
     * problem — commenting the block out moved it into a comment, the stripper
     * removed it, and the check reported success for a disabled feature. A guard
     * that passes when its subject is deleted is not a guard.
     */
    @Test
    fun `the preset guide bracket and silhouette are drawn, not commented out`() {
        val raw = overlaySource.readText()
        for (call in listOf("drawTargetBracket(", "drawFootMarker(")) {
            val live = raw.lines().count { line ->
                line.contains(call) && !line.trimStart().startsWith("*") && !line.trimStart().startsWith("//")
            }
            assertTrue(
                "`$call` must be reachable in CameraOverlay, not commented out. " +
                    "부록 A keeps the bracket + silhouette to the end.",
                live > 0,
            )
        }
        assertFalse(
            "the preset-guide block is inside a block comment — see this test's KDoc",
            Regex("""/\*(?!\*).*?drawTargetBracket\(.*?\*/""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(raw),
        )
    }

    /** The stripper is load-bearing for the assertions above; pin it. */
    @Test
    fun `the comment stripper removes prose but keeps code`() {
        val probe = File.createTempFile("overlay-probe", ".kt")
        probe.deleteOnExit()
        probe.writeText(
            """
            /** Never render Text( here, and never branch on SlotMatchStatus. */
            fun CameraOverlay() {
                drawLine() // Text( in a trailing comment
            }
            """.trimIndent(),
        )
        val stripped = probe.readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .lines().joinToString("\n") { it.substringBefore("//") }
        assertFalse("block comment must be stripped", stripped.contains("SlotMatchStatus"))
        assertFalse("trailing line comment must be stripped", stripped.contains("Text("))
        assertTrue("code must survive", stripped.contains("drawLine()"))
    }
}
