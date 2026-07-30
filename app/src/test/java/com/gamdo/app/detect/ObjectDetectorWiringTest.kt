package com.gamdo.app.detect

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins that exactly **one** object detector runs per frame.
 *
 * `EfficientDetSceneDetector` used to keep `MlKitObjectDetector` as a per-frame
 * fallback and reach for it whenever it found nothing, or nothing near the centre
 * of the frame. Pointed at a blank wall, a ceiling or a desk — which is most of
 * the time a camera is open and not yet aimed — that condition is always true, so
 * **both** detectors ran on **every** frame. Device logs made it plain: 39 analysed
 * frames, 39 ML Kit object-detection calls.
 *
 * The cost was 431.9ms in the object stage and 527ms per frame, i.e. a guide that
 * updated 1.9 times a second. Each fallback also re-ran `cropBitmapProvider`, which
 * is a full YUV→RGB conversion plus a rotate plus a copy — about 3.5MB of fresh
 * bitmap per call.
 *
 * EfficientDet is roughly 3x faster per invocation than the detector it was meant
 * to replace. The regression came from **adding it in front of** the old one rather
 * than replacing it. Owner decision 2026-07-28: drop the old one.
 *
 * ## Why this is a source-text test
 *
 * `EfficientDetSceneDetector` needs a `Context`, a 4.5MB TFLite asset and the
 * MediaPipe native runtime; `MlKitObjectDetector` needs Play services. Neither can
 * be constructed on the JVM, and there is no `androidTest` source set or
 * Robolectric here. The property is about *wiring*, and wiring is visible in the
 * source, so that is what this reads.
 */
class ObjectDetectorWiringTest {

    private val mainSources: List<File> =
        File("src/main/java/com/gamdo/app").walkTopDown().filter { it.extension == "kt" }.toList()

    /** Strips comments so the assertions read code, not the prose explaining it. */
    private fun codeOf(file: File): String =
        file.readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .lines().joinToString("\n") { it.substringBefore("//") }

    @Test
    fun `the source tree this test walks is actually there`() {
        assertTrue(
            "no Kotlin sources found under src/main/java/com/gamdo/app — this test " +
                "would pass vacuously. Check the working directory assumption (app/).",
            mainSources.size > 50,
        )
    }

    /**
     * Note the declaration is excluded, not the file.
     *
     * A first draft matched `MlKitObjectDetector(` anywhere and failed on the clean
     * tree, because `class MlKitObjectDetector(` matches too. Excluding the whole
     * declaring file would have been the easy fix and the wrong one — a fallback
     * re-added inside `MlKitDetectors.kt` is exactly as expensive as one added
     * anywhere else, and would have gone unseen.
     */
    @Test
    fun `the retired ML Kit object detector is not constructed anywhere in production`() {
        val offenders = mainSources.flatMap { file ->
            codeOf(file).lines().withIndex()
                .filter { (_, line) ->
                    line.contains("MlKitObjectDetector(") &&
                        !Regex("""\b(class|interface|object)\s+MlKitObjectDetector""").containsMatchIn(line)
                }
                .map { (i, line) -> "${file.name}:${i + 1}: ${line.trim()}" }
        }

        assertEquals(
            "MlKitObjectDetector must have no production construction site. Running it " +
                "alongside EfficientDet cost 527ms per frame — see this test's KDoc. If " +
                "EfficientDet is genuinely unusable on some device, the answer is to say " +
                "so (it logs), not to run two detectors on every frame.\n" +
                offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * The face and pose ML Kit wrappers are *not* retired — they are the only
     * detectors for their subjects. This asserts the deletion was surgical, so a
     * later reader does not conclude the whole ML Kit dependency went away.
     *
     * ## Why the whole tree, and not `CameraScreen.kt`
     *
     * This used to read only `CameraScreen.kt`, because that is where the stack
     * happened to be constructed. That was never the property — the property is
     * "these two are still constructed in production" — and the file name was an
     * incidental fact that went stale the moment the build moved to
     * `camera/SceneDetectorWarmup.kt` so it could start before the camera screen
     * composes. A single-file haystack fails on a move that changed nothing about
     * what is wired, which is a test reporting on itself rather than on the code.
     * Scanning every source is what the sibling test above already does, and it
     * cannot go stale the next time the construction site moves.
     *
     * The declaration exclusion comes with the wider haystack and is not optional:
     * over the whole tree, `class MlKitFaceDetector(` would satisfy a bare
     * `contains` and the assertion would pass with nothing constructing anything.
     * It does not match today only because neither class takes constructor
     * parameters — one added parameter would quietly hollow this out.
     */
    @Test
    fun `the ML Kit face detector is wired and pose is not`() {
        fun constructedAnywhere(name: String): Boolean = mainSources.any { file ->
            codeOf(file).lines().any { line ->
                line.contains("$name(") &&
                    !Regex("""\b(class|interface|object)\s+$name""").containsMatchIn(line)
            }
        }

        assertTrue("face detection must stay wired", constructedAnywhere("MlKitFaceDetector"))
        assertFalse("pose detection must not be wired in V3.1", constructedAnywhere("MlKitPoseDetector"))
    }
}
