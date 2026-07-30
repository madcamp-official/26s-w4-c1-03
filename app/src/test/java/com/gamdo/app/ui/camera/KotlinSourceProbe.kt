package com.gamdo.app.ui.camera

import java.io.File

/**
 * Reads Kotlin source as *code*, for the assertions that cannot be made any other
 * way.
 *
 * Three camera guards — [ShutterSurvivalTest], [CameraTeardownGateTest],
 * [CameraBindingOwnerTest] — assert things about `CameraScreen`, which is a
 * `@Composable` and cannot be executed here: this module has no `androidTest`
 * source set and no Robolectric. Reading the source is a poor substitute for
 * running it and it is the only one available, so the reading itself had better
 * not be the weak link.
 *
 * It was, twice, in one afternoon. Both bugs made a guard *pass* while checking
 * nothing:
 *
 *  - a stripper that removed line comments but not block comments let a KDoc
 *    satisfy — or violate — an assertion about code. The prose explaining why a
 *    screen must not read `LocalLifecycleOwner` was itself reported as reading it.
 *  - a brace matcher that waited for end-of-line closed a `catch` clause on the
 *    *following* clause's body, so "the cancellation branch does not toast" was
 *    reading the branch that does.
 *
 * Hence one implementation, here, with [KotlinSourceProbeTest] pinning it.
 */
internal object KotlinSourceProbe {

    /**
     * Source lines with every comment blanked and **line numbers preserved**.
     *
     * Comment characters become spaces rather than disappearing, so an offender
     * can still be reported as `line N` against what a reader sees in the editor.
     * Blanking rather than deleting is also what keeps a multi-line KDoc from
     * shifting every assertion below it.
     *
     * Not a Kotlin parser: a comment opener appearing inside a string literal is
     * treated as a comment. That is survivable here — the camera sources have no
     * such literal, and the failure direction is a guard that reads *less* code
     * than it should, which shows up as a test that cannot find its marker rather
     * than one that silently agrees.
     *
     * (Kotlin nests block comments, so writing an unpaired opener in a KDoc
     * comments out the code beneath it. This file learned that the hard way.)
     */
    fun codeLines(file: File): List<String> = stripComments(file.readText()).lines()

    fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        var inBlock = false
        while (i < text.length) {
            val two = if (i + 1 < text.length) text.substring(i, i + 2) else ""
            when {
                inBlock && two == "*/" -> { out.append("  "); i += 2; inBlock = false }
                inBlock -> { out.append(if (text[i] == '\n') '\n' else ' '); i++ }
                two == "/*" -> { out.append("  "); i += 2; inBlock = true }
                two == "//" -> while (i < text.length && text[i] != '\n') { out.append(' '); i++ }
                else -> { out.append(text[i]); i++ }
            }
        }
        return out.toString()
    }

    /**
     * The line range of the block opened on the first line containing [marker].
     *
     * Two details are load-bearing, and each of them was a bug first:
     *
     *  - counting starts **at the marker**, not at the start of its line, because
     *    a clause is written `} catch (…) {` and the leading brace would close the
     *    previous block and report a one-line body.
     *  - it returns on the **closing brace itself**, not at end of line, because
     *    `} finally {` closes and reopens on one line and waiting for the line to
     *    end would run the block on into the next one.
     */
    fun blockAt(marker: String, lines: List<String>): IntRange {
        val start = lines.indexOfFirst { it.contains(marker) }
        require(start >= 0) { "marker not found: $marker" }
        var depth = 0
        var opened = false
        for (i in start until lines.size) {
            val text = if (i == start) lines[i].substring(lines[i].indexOf(marker)) else lines[i]
            for (ch in text) {
                if (ch == '{') { depth++; opened = true }
                if (ch == '}') {
                    depth--
                    if (opened && depth == 0) return start..i
                }
            }
        }
        error("unbalanced braces after: $marker")
    }
}
