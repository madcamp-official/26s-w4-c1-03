package com.gamdo.app.ui.reference

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the one property of the 내 감도 만들기 sheet that decides whether it stays open:
 * **the sheet body claims pointer input of its own.**
 *
 * ## The defect
 *
 * The sheet is a scrim with `clickable(onDismiss)` and, above it, the sheet body. The
 * body handled no pointer input — `background`, `clip` and `padding` do not — so
 * Compose's hit test walked straight past it to the scrim underneath. Tapping the
 * analysed photo in the 구도/색감 step dismissed the sheet mid-flow. So did the
 * headings, the 적용 범위 label, and the padding. Only the buttons and the scope chips
 * survived, because each of those is a pointer handler in its own right — which is
 * exactly why the report arrived as "tapping the photo closes it" rather than as
 * "most of the sheet closes it".
 *
 * The comment that used to sit above the scrim asserted the opposite: that being
 * siblings in a Box was enough, because "Compose hit-tests the topmost node at a
 * point". It hit-tests the topmost node that *handles pointer input*. A confidently
 * wrong explanation next to the code is how this comes back, so the comment is
 * corrected and this test holds the behaviour.
 *
 * ## Why source text and not a rendered assertion
 *
 * The honest answer is that the real property — which screen regions dismiss the
 * sheet — is a Compose hit-testing fact, and it cannot be evaluated here. This module
 * has no `androidTest` source set and no Robolectric, so no composable in it can be
 * laid out, hit-tested or clicked on the JVM.
 *
 * A pure-Kotlin "model" of the geometry (scrim rect, sheet rect, tap point) would run
 * green and prove nothing: it would be testing arithmetic written in this file, and it
 * would keep passing after someone deleted the modifier that actually fixes the bug.
 * That is a hollow test and it is worse than none.
 *
 * What is checkable is that the fix is present, in the same way `CameraOverlayD2Test`
 * checks a D2 property it also cannot render. Verifying the *behaviour* stays with the
 * device: tap the photo inside the sheet, and the sheet stays open.
 */
class ReferenceCreateSheetDismissTest {

    private val sheetSource =
        File("src/main/java/com/gamdo/app/ui/reference/ReferenceCreateSheet.kt")

    /** Strips comments so the assertions read code, not the prose explaining it. */
    private fun code(): String =
        sheetSource.readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .lines().joinToString("\n") { it.substringBefore("//") }

    @Test
    fun `the sheet source this test guards actually exists`() {
        assertTrue(
            "ReferenceCreateSheet.kt not found at ${sheetSource.absolutePath} — if the " +
                "file moved, repoint this test rather than deleting it.",
            sheetSource.isFile,
        )
        assertTrue("this should be the create sheet", code().contains("fun ReferenceCreateSheet("))
    }

    @Test
    fun `the scrim still dismisses`() {
        assertTrue(
            "the scrim's dismiss click is gone — tapping outside the sheet has to " +
                "close it, that half was never broken",
            code().contains("clickable(onClick = onDismiss)"),
        )
    }

    @Test
    fun `the sheet body claims pointer input so taps do not reach the scrim`() {
        val body = sheetBody()
        assertTrue(
            "The sheet body carries no pointer-input modifier, so every part of it " +
                "that is not itself a button falls through to the scrim's " +
                "clickable(onDismiss) and closes the sheet — the photo, the headings, " +
                "the 적용 범위 label, the padding. Being drawn on top is not enough: " +
                "Compose hit-tests the topmost node that HANDLES pointer input.\n" +
                "Sheet body modifier chain was:\n$body",
            body.contains("pointerInput"),
        )
    }

    /**
     * The claim has to cover the padding too, or the 20dp inset around the content is
     * a dismiss target while the content inside it is not — the same bug, one ring in.
     */
    @Test
    fun `the pointer claim wraps the padding rather than sitting inside it`() {
        val body = sheetBody()
        val claim = body.indexOf("pointerInput")
        val padding = body.indexOf("padding(20.dp)")
        assertTrue("expected the sheet's own padding in the chain", padding >= 0)
        assertTrue(
            "pointerInput must come before padding(20.dp) in the chain, or the inset " +
                "is outside the claimed area and taps on it still dismiss:\n$body",
            claim in 0 until padding,
        )
    }

    /**
     * There is exactly one scrim and one sheet body. If a second dismissing scrim
     * appears, the reasoning above stops covering the screen.
     */
    @Test
    fun `there is exactly one dismissing scrim`() {
        val scrims = Regex("""clickable\(onClick = onDismiss\)""").findAll(code()).count()
        assertEquals("one scrim, one dismiss", 1, scrims)
    }

    /**
     * The sheet body's modifier chain: from the `Column` that is aligned to
     * `BottomCenter` up to the `) {` that closes the `Column(` call.
     *
     * The terminator is anchored to the start of a line, because a modifier that takes
     * a trailing lambda — which is exactly what the fix is — also contains `) {`.
     */
    private fun sheetBody(): String {
        val code = code()
        val start = code.indexOf("align(Alignment.BottomCenter)")
        assertTrue(
            "could not find the sheet body Column — repoint this test at whatever " +
                "replaced it rather than deleting it",
            start >= 0,
        )
        val end = Regex("""\n\s*\)\s*\{""").find(code, start)?.range?.first
        return code.substring(start, end ?: code.length)
    }
}
