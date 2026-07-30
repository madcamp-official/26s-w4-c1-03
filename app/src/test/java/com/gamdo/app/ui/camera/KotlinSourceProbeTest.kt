package com.gamdo.app.ui.camera

import com.gamdo.app.ui.camera.KotlinSourceProbe.blockAt
import com.gamdo.app.ui.camera.KotlinSourceProbe.stripComments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The probe the camera guards stand on.
 *
 * Every assertion in [ShutterSurvivalTest], [CameraTeardownGateTest] and
 * [CameraBindingOwnerTest] is "this marker is / is not inside that block". A probe
 * that answered "the whole file" or "one line" would make half of them pass
 * unconditionally and there would be nothing to notice. See [KotlinSourceProbe]
 * for the two ways that has already happened.
 */
class KotlinSourceProbeTest {

    // ---- stripComments ---------------------------------------------------

    /**
     * Blanked, not deleted — every character keeps its column so an offender can
     * be reported at the position a reader sees in the editor.
     */
    @Test
    fun `a line comment is blanked in place`() {
        val source = "val a = 1 // hi"
        val stripped = stripComments(source)
        assertEquals("columns must be preserved", source.length, stripped.length)
        assertEquals("val a = 1", stripped.trimEnd())
    }

    @Test
    fun `a block comment is blanked in place`() {
        val source = "val a = /* two */1"
        val stripped = stripComments(source)
        assertEquals("columns must be preserved", source.length, stripped.length)
        assertFalse("the comment text must be gone", stripped.contains("two"))
        assertTrue("the code before it survives", stripped.startsWith("val a = "))
        assertTrue("the code after it survives, in place", stripped.endsWith("1"))
        assertEquals(
            "the trailing `1` must not move",
            source.indexOf('1', startIndex = 9),
            stripped.indexOf('1'),
        )
    }

    /**
     * The one that mattered: prose *about* a symbol must not read as a use of it.
     * The KDoc explaining why `CameraScreen` must not touch `LocalLifecycleOwner`
     * was being reported as touching it.
     */
    @Test
    fun `a KDoc mentioning a symbol is not a use of it`() {
        val source = """
            /**
             * Never read LocalLifecycleOwner here.
             */
            fun f() = other()
        """.trimIndent()
        val lines = stripComments(source).lines()
        assertFalse(
            "the KDoc must not count as a use",
            lines.any { it.contains("LocalLifecycleOwner") },
        )
        assertTrue("the code must survive", lines.any { it.contains("fun f() = other()") })
    }

    @Test
    fun `line numbers survive a multi-line comment`() {
        val source = """
            one()
            /* a
               comment
               spanning */
            target()
        """.trimIndent()
        val lines = stripComments(source).lines()
        assertEquals("five lines in, five lines out", 5, lines.size)
        assertEquals(
            "target() must still be reportable as line 5",
            4,
            lines.indexOfFirst { it.contains("target()") },
        )
    }

    @Test
    fun `code after a block comment closes is kept`() {
        assertEquals("     val a = 1", stripComments("/*x*/val a = 1"))
    }

    // ---- blockAt ---------------------------------------------------------

    @Test
    fun `a simple block`() {
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
        assertFalse("the leading call must fall outside", 0 in range)
        assertFalse("the trailing call must fall outside", 7 in range)
    }

    /** `} catch (…) {` — the leading brace must not close the `try`. */
    @Test
    fun `a catch clause stops at its own closing brace`() {
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
        assertTrue("its own body is inside", 3 in range)
        assertFalse("the finally body must fall outside", 5 in range)
    }

    /** A lambda argument followed by siblings — the `AndroidView` shape. */
    @Test
    fun `a lambda argument ends before the next argument`() {
        val lines = """
            AndroidView(
                factory = { ctx ->
                    thing(ctx).apply {
                        bind()
                    }
                },
                onRelease = { other() },
            )
        """.trimIndent().lines()
        val range = blockAt("factory = { ctx ->", lines)
        assertEquals(1..5, range)
        assertTrue("the nested call is inside", 3 in range)
        assertFalse("the sibling argument must fall outside", 6 in range)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a missing marker is loud`() {
        blockAt("nothing like this", listOf("fun f() { }"))
    }

    @Test(expected = IllegalStateException::class)
    fun `an unbalanced block is loud`() {
        blockAt("fun f", listOf("fun f() {", "    open()"))
    }
}
