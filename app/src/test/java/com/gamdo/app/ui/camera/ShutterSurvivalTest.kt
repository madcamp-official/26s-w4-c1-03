package com.gamdo.app.ui.camera

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A photo the user asked for is not thrown away because they stopped waiting.
 *
 * ## The defect
 *
 * Shutter, then 앨범 0.3s later: no `CaptureLatency` line, no file, nothing in the
 * album. Reproduced on SM-G970N 2026-07-30. Two independent causes, and the fix
 * needs both halves:
 *
 * 1. `scope` is `rememberCoroutineScope()`, so leaving the screen cancels the
 *    shutter coroutine wherever it happens to be suspended — normally inside
 *    `capture()`, which measures 290-1613ms. **This file guards that half.**
 * 2. `onDispose` unbound CameraX, which aborts the in-flight request outright.
 *    `CameraTeardownGateTest` guards that half.
 *
 * ## Why the assertions read source text
 *
 * The region is a `withContext` inside a `@Composable`, and this module has no
 * `androidTest` source set and no Robolectric, so the shutter cannot be executed
 * on the JVM at all. What *can* be checked is the property that makes it correct:
 * the span from `capture()` to `saveCameraCapture()` is one uncancellable block,
 * and the KPI tail is not in it. A test that reads the source is a poor substitute
 * for running it, and it is the only one available — see `DebugHudGateTest`, which
 * covers the same screen the same way and for the same reason.
 */
class ShutterSurvivalTest {

    private val screenSource = File("src/main/java/com/gamdo/app/ui/camera/CameraScreen.kt")

    /**
     * Source lines with `//` comments blanked, indexes preserved.
     *
     * Comments are removed so that prose *about* the region — of which there is a
     * lot, deliberately — cannot satisfy an assertion about the region.
     */
    private fun code(): List<String> = screenSource.readText().lines().map { it.substringBefore("//") }

    @Test
    fun `the screen source this test guards actually exists`() {
        assertTrue(
            "CameraScreen.kt not found at ${screenSource.absolutePath} — if the file " +
                "moved, repoint this test rather than deleting it.",
            screenSource.isFile,
        )
        assertTrue("this should be the camera host", code().any { it.contains("fun CameraScreen(") })
    }

    // ---- the uncancellable region ---------------------------------------

    @Test
    fun `the shutter has exactly one uncancellable region`() {
        val lines = code()
        val regions = lines.withIndex().filter { it.value.contains("withContext(NonCancellable)") }
        assertEquals(
            "The shutter's capture→save span is one region. Two would mean a gap " +
                "between them where a navigate-away can still drop the photo; zero " +
                "means the fix was reverted.\n" +
                regions.joinToString("\n") { "line ${it.index + 1}: ${it.value.trim()}" },
            1,
            regions.size,
        )
    }

    @Test
    fun `the capture and the save are both inside it`() {
        val lines = code()
        val region = blockAt("withContext(NonCancellable)", lines)
        for (call in listOf("controller.capture(", "captureRepository.saveCameraCapture(")) {
            val at = lines.indexOfFirst { it.contains(call) }
            assertTrue("`$call` not found at all — repoint this test.", at >= 0)
            assertTrue(
                "`$call` is at line ${at + 1}, outside the uncancellable region " +
                    "(lines ${region.first + 1}..${region.last + 1}). Leaving the camera " +
                    "screen mid-capture would cancel it and lose the photo.",
                at in region,
            )
        }
    }

    /**
     * The `CaptureLatency capture …` line is the on-device evidence that the photo
     * was written — it is step 1 of the owner's verification. Outside the region it
     * would go missing in exactly the scenario the fix is for, and the fix would
     * read as having failed.
     */
    @Test
    fun `the latency evidence line is inside it`() {
        val lines = code()
        val region = blockAt("withContext(NonCancellable)", lines)
        val at = lines.indexOfFirst { it.contains("\"capture \${it.format()}") }
        assertTrue("the `CaptureLatency capture` line disappeared — repoint this test.", at >= 0)
        assertTrue(
            "the capture-latency line is at line ${at + 1}, outside the region; a " +
                "navigate-away would save the photo and print nothing to say so.",
            at in region,
        )
    }

    /**
     * The boundary is "the photo is safe", not "everything is done".
     *
     * `sessions.final_match_score` is a session aggregate; the shot's own score is
     * already in the `captures` row written inside the region. Holding the whole
     * KPI tail uncancellable would widen the window for no user-visible gain.
     */
    @Test
    fun `the session KPI tail is outside it`() {
        val lines = code()
        val region = blockAt("withContext(NonCancellable)", lines)
        val at = lines.indexOfFirst { it.contains("guideKpiRepository.recordFinalScore(") }
        assertTrue("recordFinalScore call disappeared — repoint this test.", at >= 0)
        assertFalse(
            "the KPI tail is inside the uncancellable region (line ${at + 1}). The " +
                "region ends where the photo stops depending on this screen.",
            at in region,
        )
    }

    // ---- cancellation is not a capture failure --------------------------

    @Test
    fun `cancellation is caught ahead of the generic failure clause`() {
        val lines = code()
        val cancel = lines.indexOfFirst { it.contains("catch (t: CancellationException)") }
        val generic = lines.indexOfFirst { it.contains("catch (t: Throwable)") }
        assertTrue(
            "the shutter must catch CancellationException explicitly; `catch " +
                "(t: Throwable)` catches it too, which is how walking away from the " +
                "camera came to report a failure that had not happened.",
            cancel >= 0,
        )
        assertTrue("the generic clause disappeared — repoint this test.", generic >= 0)
        assertTrue(
            "CancellationException is caught at line ${cancel + 1}, after the generic " +
                "clause at line ${generic + 1}. Kotlin matches clauses in order, so " +
                "the generic one would win and the toast would come back.",
            cancel < generic,
        )
    }

    @Test
    fun `cancellation cannot reach the failure toast`() {
        val lines = code()
        val clause = blockAt("catch (t: CancellationException)", lines)
        val body = lines.slice(clause).joinToString("\n")
        assertTrue("a cancelled shutter must rethrow, not swallow", body.contains("throw t"))
        assertFalse(
            "the cancellation clause must not tell the user the capture failed — it " +
                "did not; the photo was written inside the uncancellable region.",
            body.contains("Toast"),
        )
    }

    /**
     * The other half, and the reason this is two clauses rather than one blanket
     * `catch (t: CancellationException)`-shaped silence: a capture that really
     * failed still has to say so. Swallowing both would be the W2-2 failure — the
     * app knowing something went wrong and saying nothing.
     */
    @Test
    fun `a real capture failure is still reported`() {
        val lines = code()
        val clause = blockAt("catch (t: Throwable)", lines)
        val body = lines.slice(clause).joinToString("\n")
        assertTrue(
            "a genuine capture failure must still surface — silence about a failure " +
                "is its own defect.",
            body.contains("Toast.makeText") && body.contains("촬영에 실패했어요"),
        )
    }

    // ---- the matcher these assertions stand on --------------------------

    /**
     * Brace-matches the block opened on the first line containing [marker].
     *
     * Pinned by [the block matcher finds a block's extent] because every assertion
     * above is a containment claim: a matcher that returned the whole file would
     * make half of them pass unconditionally and the other half fail.
     */
    private fun blockAt(marker: String, lines: List<String>): IntRange {
        val start = lines.indexOfFirst { it.contains(marker) }
        require(start >= 0) { "marker not found: $marker" }
        var depth = 0
        var opened = false
        for (i in start until lines.size) {
            // From the marker, not from the start of its line. A `catch` clause is
            // written `} catch (…) {`, and counting that leading brace would close
            // the *previous* block and report a one-line body — which is a matcher
            // that silently agrees with everything.
            val text = if (i == start) lines[i].substring(lines[i].indexOf(marker)) else lines[i]
            for (ch in text) {
                if (ch == '{') { depth++; opened = true }
                // Returns on the closing brace itself, not at end of line. `}` and
                // `{` share a line in `} catch (…) {` and `} finally {`, so waiting
                // for the line to end would run the clause on into the next one —
                // and a `catch (CancellationException)` that swallowed the *generic*
                // clause's body would read as correct while asserting nothing.
                if (ch == '}') {
                    depth--
                    if (opened && depth == 0) return start..i
                }
            }
        }
        error("unbalanced braces after: $marker")
    }

    @Test
    fun `the block matcher finds a block's extent`() {
        val lines = """
            before()
            val x = withContext(NonCancellable) {
                inside()
                if (true) {
                    deeper()
                }
            }
            after()
        """.trimIndent().lines()
        val range = blockAt("withContext(NonCancellable)", lines)
        assertEquals(1..6, range)
        assertFalse("the trailing call must fall outside", 7 in range)
        assertFalse("the leading call must fall outside", 0 in range)
    }

    /** The shape the two catch assertions actually run against. */
    @Test
    fun `the block matcher survives a leading close brace`() {
        val lines = """
            try {
                work()
            } catch (t: Throwable) {
                report()
            } finally {
                always()
            }
        """.trimIndent().lines()
        val range = blockAt("catch (t: Throwable)", lines)
        assertEquals(2..4, range)
        assertTrue("the clause body must be inside", 3 in range)
        assertFalse("the finally body must fall outside", 5 in range)
    }
}
