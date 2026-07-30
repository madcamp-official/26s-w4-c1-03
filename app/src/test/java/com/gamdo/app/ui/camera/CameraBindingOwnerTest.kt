package com.gamdo.app.ui.camera

import com.gamdo.app.ui.camera.KotlinSourceProbe.blockAt
import com.gamdo.app.ui.camera.KotlinSourceProbe.codeLines
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The camera is bound to the Activity, not to the screen.
 *
 * ## The defect this exists for
 *
 * `LocalLifecycleOwner.current` inside a Navigation-Compose `composable { }` is
 * **that destination's `NavBackStackEntry`**, not the Activity — `NavHost` swaps it
 * in through `LocalOwnersProvider`. Handing it to `bindToLifecycle` bound the
 * camera to the screen, so tapping 앨범 dropped the entry below STARTED and CameraX
 * detached the use cases and aborted the in-flight capture itself:
 *
 * ```
 * 16:18:22.696  Use cases [...] now DETACHED for camera
 * 16:18:22.703  ImageCaptureException: Camera is closed.
 *                 at ImageCapture.onStateDetached(ImageCapture.java:1029)
 * ```
 *
 * Seven milliseconds after the tap — before the shutter coroutine, before
 * `onDispose`, before [CameraTeardownGate] saw anything, which is why its counters
 * all read zero while the photo still went missing. Measured on SM-G970N
 * 2026-07-30.
 *
 * ## Why it is worth a test file
 *
 * Both owners are `LifecycleOwner`. Both compile. Both look right. The wrong one
 * fails only when someone navigates away during the few hundred milliseconds a
 * capture is in flight, which is not a thing anyone does by hand. There is no type,
 * no lint and no review heuristic that separates them — only the knowledge that
 * `LocalLifecycleOwner` means something different in here, which is exactly the
 * kind of knowledge that leaves with the person who acquired it.
 *
 * So: `CameraScreen` may not read `LocalLifecycleOwner` at all, and the one file
 * that does is the one whose whole job is to explain why it is rejecting it.
 */
class CameraBindingOwnerTest {

    private val cameraDir = File("src/main/java/com/gamdo/app/ui/camera")
    private val screenSource = File(cameraDir, "CameraScreen.kt")
    private val ownerSource = File(cameraDir, "CameraBindingOwner.kt")

    private fun code(file: File): List<String> = codeLines(file)

    @Test
    fun `the sources this test guards actually exist`() {
        assertTrue(
            "CameraScreen.kt not found at ${screenSource.absolutePath} — if the file " +
                "moved, repoint this test rather than deleting it.",
            screenSource.isFile,
        )
        assertTrue(
            "CameraBindingOwner.kt not found at ${ownerSource.absolutePath}. If the " +
                "resolver moved, repoint this test; if it was inlined back into the " +
                "screen, read this file's KDoc first.",
            ownerSource.isFile,
        )
    }

    // ---- the trap -------------------------------------------------------

    @Test
    fun `the camera screen never reads LocalLifecycleOwner`() {
        val offenders = code(screenSource).withIndex()
            .filter { (_, line) -> line.contains("LocalLifecycleOwner") }
            .map { (i, line) -> "line ${i + 1}: ${line.trim()}" }
        assertEquals(
            "Inside a Navigation-Compose destination, LocalLifecycleOwner is the " +
                "NavBackStackEntry — binding the camera to it makes navigating away " +
                "abort the capture. Use rememberCameraBindingOwner().\n" +
                offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * Stated over the whole package, not just the screen: the next overlay or panel
     * to need a lifecycle owner will reach for the same local and find the same
     * wrong answer.
     */
    @Test
    fun `only the resolver reads LocalLifecycleOwner in this package`() {
        val offenders = cameraDir.listFiles { f: File -> f.extension == "kt" }
            .orEmpty()
            .filter { it.name != ownerSource.name }
            .flatMap { file ->
                code(file).withIndex()
                    .filter { (_, line) -> line.contains("LocalLifecycleOwner") }
                    .map { (i, line) -> "${file.name}:${i + 1}: ${line.trim()}" }
            }
        assertEquals(
            "LocalLifecycleOwner in ui/camera means the back-stack entry. Route it " +
                "through CameraBindingOwner.kt, which documents why.\n" +
                offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `the camera binds to the resolved owner and nothing else`() {
        val lines = code(screenSource)
        val binds = lines.withIndex().filter { (_, line) -> line.contains("controller.bind(") }
        assertEquals(
            "there must be exactly one bind site: two would mean two bindings, and " +
                "unbind() is process-wide.\n" +
                binds.joinToString("\n") { "line ${it.index + 1}: ${it.value.trim()}" },
            1,
            binds.size,
        )
        val (at, line) = binds.single()
        assertTrue(
            "line ${at + 1} binds to something other than the resolved Activity " +
                "owner: ${line.trim()}",
            line.contains("controller.bind(cameraLifecycleOwner)"),
        )
    }

    @Test
    fun `the resolved owner comes from the resolver`() {
        val lines = code(screenSource)
        val assignment = lines.firstOrNull { it.contains("val cameraLifecycleOwner") }
        assertTrue(
            "`val cameraLifecycleOwner` disappeared — repoint this test rather than " +
                "deleting it.",
            assignment != null,
        )
        assertTrue(
            "the bound owner must be produced by rememberCameraBindingOwner(). " +
                "Found: ${assignment!!.trim()}",
            assignment.contains("rememberCameraBindingOwner()"),
        )
    }

    // ---- link 1 of the release chain ------------------------------------

    /**
     * The gate's whole argument that no binding is ever orphaned starts here: a
     * binding exists only for a composition that was *applied*, because
     * `AndroidView`'s factory does not run for an abandoned one — and an applied
     * composition always runs `onDispose`. Bind from anywhere else (a `remember`,
     * a `LaunchedEffect`) and that stops being true, silently, and the camera can
     * be left on with no screen and nothing scheduled to release it.
     */
    @Test
    fun `the bind happens inside the AndroidView factory`() {
        val lines = code(screenSource)
        val factory = blockAt("factory = { ctx ->", lines)
        val bind = lines.indexOfFirst { it.contains("controller.bind(") }
        assertTrue(
            "the bind is at line ${bind + 1}, outside the AndroidView factory " +
                "(lines ${factory.first + 1}..${factory.last + 1}). Only the factory " +
                "guarantees the composition was applied, which is what guarantees " +
                "onDispose will run and release it.",
            bind in factory,
        )
    }

    @Test
    fun `the stale binding is spent inside the same factory, before the bind`() {
        val lines = code(screenSource)
        val factory = blockAt("factory = { ctx ->", lines)
        val release = lines.indexOfFirst { it.contains("cameraTeardownGate.releaseBeforeBind()") }
        val bind = lines.indexOfFirst { it.contains("controller.bind(") }
        assertTrue("releaseBeforeBind() left the factory (line ${release + 1})", release in factory)
        assertTrue(
            "releaseBeforeBind() must precede bind() — unbind() is unbindAll(), so " +
                "in the other order the old screen's release tears down the new " +
                "screen's camera. Now that the binding outlives the destination, " +
                "there is no lifecycle left to clean that up.",
            release < bind,
        )
    }

    // ---- the fallback ---------------------------------------------------

    @Test
    fun `a context with no Activity behind it is reported, not swallowed`() {
        val body = ownerSource.readText()
        assertTrue(
            "the resolver must prefer the hosting ComponentActivity",
            body.contains("findComponentActivity()"),
        )
        assertTrue(
            "falling back to the destination owner reinstates the defect, so it has " +
                "to say so — a silent fallback is a silent regression.",
            body.contains("Log.w("),
        )
    }

}
