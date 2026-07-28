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
     * The coexistence fix, stated as a property.
     *
     * The auto resolver latches a layout within ~3 frames of almost any scene and
     * never un-latches inside a session. While the preset-guide block was gated on
     * `fixedLayout == null`, that latch silently deleted the bracket, silhouette,
     * foot marker and outline for the rest of the session — all six style presets
     * rendered identically. The gate is gone; this fails if it comes back.
     */
    @Test
    fun `the preset guide is not gated on the absence of a fixed layout`() {
        val offenders = code().lines()
            .withIndex()
            .filter { (_, line) -> line.contains("fixedLayout == null") }
            .map { (i, line) -> "line ${i + 1}: ${line.trim()}" }

        assertEquals(
            "The style-preset guide must draw alongside a latched fixed layout, not " +
                "instead of it (owner decision, remain_plan 2026-07-28). A " +
                "`fixedLayout == null` guard here re-hides it for the whole session.\n" +
                offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
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
