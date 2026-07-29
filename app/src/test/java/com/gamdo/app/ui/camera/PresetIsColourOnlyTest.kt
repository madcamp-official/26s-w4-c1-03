package com.gamdo.app.ui.camera

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O-13 (1) — 프리셋은 색감 전용이다. 가이드를 건드리지 않는다.
 *
 * ## What shipped, and what it looked like
 *
 * `CameraScreen` ran `viewModel.setStyleTarget(activePreset.toStyleTarget())` from a
 * `LaunchedEffect`. `StylePreset.toStyleTarget()` maps `composition.subjectPosition`
 * to `subjectAnchorX` — `third_left` → 1/3, `third_right` → 2/3 — and
 * `GenericLayoutSynthesizer.transform` then re-centres **every slot of the layout the
 * AI just chose** on that anchor, and rescales them by `subjectScaleRange`. So in
 * `presets.json` as shipped:
 *
 * | preset | anchorX | subject scale |
 * |---|---|---|
 * | `clean_social` | 0.50 | 0.35–0.55 |
 * | `candid_feed` | **0.33** | 0.30–0.50 |
 * | `bright_review` | 0.50 | **0.45–0.70** |
 * | `soft_film` | **0.67** | 0.32–0.52 |
 * | `casual_portrait` | 0.50 | 0.40–0.62 |
 * | `night_street` | **0.33** | 0.30–0.50 |
 *
 * Tapping 부드러운 필름 threw the brackets to the right third of the frame; tapping
 * 밤거리 threw them to the left third. That is the "filter behaviour is backwards"
 * the owner reported: the colour control moved the composition and nothing else.
 *
 * ## Why a source test
 *
 * The coupling lives in a `LaunchedEffect` inside a `@Composable`. There is no
 * `androidTest` source set and no Robolectric on the classpath, so the screen cannot
 * be composed on the JVM. Reading the source is the only gate available — the same
 * reasoning, and the same precedent, as [CameraOverlayD2Test].
 *
 * A failure here is not a rename. It means a preset is steering the guide again, and
 * O-13 requires an explicit owner reversal before that is allowed.
 */
class PresetIsColourOnlyTest {

    private val cameraScreen = File("src/main/java/com/gamdo/app/ui/camera/CameraScreen.kt")

    private fun code(file: File): String =
        file.readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .lines().joinToString("\n") { it.substringBefore("//") }

    @Test
    fun `the camera screen this test guards actually exists`() {
        assertTrue(cameraScreen.absolutePath, cameraScreen.isFile)
    }

    /**
     * Every `x.toStyleTarget()` in production, with `x` captured.
     *
     * The receiver, not the line. The first version of this test filtered out any
     * *line* mentioning `referenceTarget`, and re-injecting the defect as
     *
     * ```
     * if (referenceTarget == null) { activePreset?.let { viewModel.setStyleTarget(it.toStyleTarget()) } }
     * ```
     *
     * sailed straight through it — the guard was green while the bug was back. A
     * whole-line allowlist is not a guard, it is a suggestion.
     */
    private fun styleTargetReceivers(): List<String> =
        File("src/main/java/com/gamdo/app")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.name == "MatchScoreCalculator.kt" } // the declaration site
            .flatMap { file ->
                Regex("""(\w+)\s*\??\.toStyleTarget\s*\(""")
                    .findAll(code(file))
                    .map { "${file.name}: ${it.groupValues[1]}.toStyleTarget()" }
            }
            .toList()

    @Test
    fun `only a reference is ever converted into a guide target`() {
        // `ResolvedStyle.toStyleTarget()` stays — a reference may still carry a
        // composition (O-13 (2)). `StylePreset.toStyleTarget()` keeps its declaration
        // too, because the read-only `P2ValueDumpTest` is 담당 B's comparison baseline
        // and imports it. What must not exist is a *production* caller for it.
        assertEquals(
            listOf("CameraScreen.kt: referenceTarget.toStyleTarget()"),
            styleTargetReceivers(),
        )
    }

    @Test
    fun `the camera screen never hand-builds a composition target either`() {
        // Closes the route around the extension: assembling `StyleTarget(subjectAnchorX
        // = ...)` from `preset.composition` would reproduce the bug without ever
        // calling `toStyleTarget`.
        val banned = listOf("subjectAnchorX", "subjectAnchorY", "subjectScaleRange", "subjectPosition")
        val hits = code(cameraScreen).lines().withIndex().flatMap { (i, line) ->
            banned.filter { line.contains(it) }.map { "${i + 1}: $it — ${line.trim()}" }
        }
        assertEquals(
            "CameraScreen.kt must not touch composition fields (O-13):\n" + hits.joinToString("\n"),
            emptyList<String>(),
            hits,
        )
    }
}
