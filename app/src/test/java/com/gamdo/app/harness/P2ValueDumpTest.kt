package com.gamdo.app.harness

import com.gamdo.app.data.CardFeature
import com.gamdo.app.data.FeedbackSignal
import com.gamdo.app.data.PresetProfile
import com.gamdo.app.data.ProfileEngine
import com.gamdo.app.data.preset.StylePreset
import com.gamdo.app.detect.BrightnessSample
import com.gamdo.app.detect.DetectionResult
import com.gamdo.app.detect.FaceObservation
import com.gamdo.app.detect.FrameFeatureCalculator
import com.gamdo.app.detect.FrameFeatureInput
import com.gamdo.app.detect.ImageMetrics
import com.gamdo.app.detect.NormalizedBox
import com.gamdo.app.detect.PoseLandmarkPoint
import com.gamdo.app.detect.PoseObservation
import com.gamdo.app.detect.ProblemDiagnoser
import com.gamdo.app.camera.TiltReading
import com.gamdo.app.guide.AlignmentEngine
import com.gamdo.app.guide.MatchScoreCalculator
import com.gamdo.app.guide.parseGuideConfig
import com.gamdo.app.guide.toStyleTarget
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs the P2 modules over the assets that actually ship in the APK and prints
 * every value, so the numbers can be eyeballed without a device or a UI. The
 * other tests in `src/test` assert behaviour on synthetic inputs; this one exists
 * to answer "what does the real data produce?".
 *
 *   ./gradlew.bat :app:testDebugUnitTest --tests "com.gamdo.app.harness.P2ValueDumpTest"
 *
 * Assertions are limited to asset sanity, so the dump keeps working while
 * parameters are tuned.
 */
class P2ValueDumpTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val assets = File("src/main/assets")

    @Serializable
    private data class CardJson(
        val id: String,
        val subjectScale: Float,
        val subjectPosition: Float,
        val headroom: Float,
        val backgroundRatio: Float,
        val brightness: Float,
        val lightType: String,
        val colorTemperature: Float,
        val saturation: Float,
        val contrast: Float,
        val sharpness: Float,
        val grain: Float,
        val candidness: Float,
        val framing: Float,
    ) {
        fun toFeature() = CardFeature(
            id, subjectScale, subjectPosition, headroom, backgroundRatio, brightness,
            lightType, colorTemperature, saturation, contrast, sharpness, grain, candidness, framing,
        )
    }

    @Serializable
    private data class CardsFile(val v: Int, val cards: List<CardJson>)

    private fun presets(): List<StylePreset> =
        json.decodeFromString(File(assets, "presets.json").readText())

    private fun cards(): List<CardFeature> =
        json.decodeFromString<CardsFile>(File(assets, "cards.json").readText()).cards.map { it.toFeature() }

    @Test
    fun `guide targets from the bundled presets`() {
        val config = parseGuideConfig(File(assets, "guide_config.json").readText())
        println("\n=== guide_config.json ===")
        println("  smoothingWindow=${config.smoothingWindow} alignedIoU=${config.alignedIouThreshold} " +
            "recomputeMovement=${config.recomputeMovementThreshold} minPoseConf=${config.minPoseConfidence} " +
            "maxUnstableFrames=${config.maxUnstableFrames}")

        val presets = presets()
        assertEquals("presets.json must hold the 6 agreed presets", 6, presets.size)

        // A person filling the middle of the frame — the same scene for every preset,
        // so differences in the output come from preset data alone. Landmark types are
        // the ML Kit PoseLandmark constants (nose=0, shoulders=11/12, hips=23/24, ankles=27/28).
        val landmarks = listOf(
            PoseLandmarkPoint(0, 0.50f, 0.14f, 0.9f),
            PoseLandmarkPoint(11, 0.38f, 0.32f, 0.9f),
            PoseLandmarkPoint(12, 0.62f, 0.32f, 0.9f),
            PoseLandmarkPoint(23, 0.41f, 0.62f, 0.85f),
            PoseLandmarkPoint(24, 0.59f, 0.62f, 0.85f),
            PoseLandmarkPoint(27, 0.44f, 0.90f, 0.7f),
            PoseLandmarkPoint(28, 0.56f, 0.90f, 0.7f),
        )
        val features = FrameFeatureCalculator().calculate(
            FrameFeatureInput(
                detection = DetectionResult(
                    faces = listOf(
                        FaceObservation(
                            box = NormalizedBox(0.42f, 0.10f, 0.58f, 0.30f),
                            leftEyeOpenProbability = 0.9f,
                            rightEyeOpenProbability = 0.9f,
                            headEulerAngleZ = 1.2f,
                        ),
                    ),
                    pose = PoseObservation(
                        landmarks = landmarks,
                        averageInFrameLikelihood = landmarks.map { it.inFrameLikelihood }.average().toFloat(),
                    ),
                ),
                tilt = TiltReading(rollDeg = 1.4f, pitchDeg = -2.0f),
                brightness = BrightnessSample(frameMean = 0.52f),
                shake = 0.03f,
            ),
        )
        println("\n=== FrameFeatures (centred standing person, luma 0.52) ===")
        println("  personBox=${features.personBox?.pretty()} faceBox=${features.faceBox?.pretty()}")
        println("  personAreaRatio=%.3f headroom=%.3f sideMargins=(%.3f, %.3f)".format(
            features.personAreaRatio, features.headroom,
            features.sideMargins.left, features.sideMargins.right))
        println("  tilt=%.1f° pitch=%.1f° brightness=%.2f poseConf=%.2f shake=%.3f backlight=%s lowLight=%s".format(
            features.tiltDeg, features.pitchDeg, features.brightnessMean, features.poseConfidence,
            features.shake, features.backlightFlag, features.lowLightFlag))

        println("\n=== preset → StyleTarget → target frame / matchScore ===")
        println("  %-16s %-8s %-14s %-9s %s".format("preset", "anchorX", "scaleRange", "aligned", "targetFrame (L,T,R,B) · IoU · matchScore"))
        val scores = mutableMapOf<String, Float>()
        presets.forEach { preset ->
            val target = preset.toStyleTarget()
            // A fresh engine per preset: the smoothing window must not leak across rows.
            val engine = AlignmentEngine()
            var state = engine.align(features, target, config)
            repeat(config.smoothingWindow) { state = engine.align(features, target, config) }
            val weighted = MatchScoreCalculator().calculate(features, target)
            scores[preset.id] = weighted
            println("  %-16s %-8.2f %-14s %-9s (%.2f, %.2f, %.2f, %.2f) · IoU %.2f · %.3f".format(
                preset.id, target.subjectAnchorX,
                "%.2f-%.2f".format(target.subjectScaleRange.start, target.subjectScaleRange.endInclusive),
                state.aligned.toString(),
                state.targetFrame.left, state.targetFrame.top, state.targetFrame.right, state.targetFrame.bottom,
                engine.metrics().matchScore, weighted))
            assertTrue("${preset.id}: target frame must stay inside the frame",
                state.targetFrame.left >= 0f && state.targetFrame.right <= 1f &&
                    state.targetFrame.top >= 0f && state.targetFrame.bottom <= 1f)
        }
        assertTrue("the same frame must score differently per preset", scores.values.distinct().size > 1)
        println("  NOTE AlignmentEngine.metrics().matchScore is the IoU, not the §4.2 weighted score" +
            " — MatchScoreCalculator is not wired into the camera pipeline yet.")
    }

    @Test
    fun `profile and recommendations from the bundled cards`() {
        val cards = cards()
        assertEquals("cards.json must hold the 16 v1 cards", 16, cards.size)
        val presetProfiles = presets().map { it.toPresetProfileBestEffort() }

        println("\n=== ProfileEngine over assets/cards.json ===")
        listOf(
            "film-ish picks   " to listOf("card_01", "card_06", "card_10", "card_14"),
            "bright/sharp     " to listOf("card_03", "card_09", "card_15"),
            "mixed picks      " to listOf("card_04", "card_08", "card_12"),
        ).forEach { (label, ids) ->
            val picked = cards.filter { it.id in ids }
            val profile = ProfileEngine.build(picked, presetProfiles)
            println("  $label ${ids.joinToString(",")}")
            println("      summary=\"${profile.summary}\"  recommended=${profile.recommendedPresetIds}")
            println("      composition=" + profile.composition.entries.joinToString {
                "${it.key}=%.2f(±%.2f)".format(it.value.mean, it.value.confidence)
            })
            println("      color=" + profile.color.entries.joinToString {
                "${it.key}=%.2f(±%.2f)".format(it.value.mean, it.value.confidence)
            })
            val afterFeedback = ProfileEngine.applyFeedback(profile, FeedbackSignal.COMPOSITION_GOOD_COLOR_BAD)
            println("      after \"색감이 아쉬움\" feedback: colorTemperature %.0fK → %.0fK".format(
                profile.color.getValue("colorTemperature").mean,
                afterFeedback.color.getValue("colorTemperature").mean))
            assertEquals("feedback must return 3 recommendations", 3, profile.recommendedPresetIds.size)
        }

        // Why the recommendation looks colour-temperature-driven: the distance sum in
        // ProfileEngine.recommend mixes Kelvin with 0..1 dimensions unnormalized.
        val profile = ProfileEngine.build(cards.filter { it.id in listOf("card_03", "card_09") }, presetProfiles)
        val all = profile.composition + profile.color
        println("\n  per-dimension distance to each preset (unnormalized — Kelvin dominates):")
        presetProfiles.forEach { preset ->
            val merged = preset.composition + preset.color
            val parts = all.entries.map { (key, value) ->
                key to kotlin.math.abs(value.mean - (merged[key] ?: value.mean))
            }
            println("      %-16s total=%.1f  %s".format(
                preset.id, parts.sumOf { it.second.toDouble() },
                parts.sortedByDescending { it.second }.take(3).joinToString { "${it.first}=%.2f".format(it.second) }))
        }
    }

    @Test
    fun `problem diagnoser over representative image metrics`() {
        println("\n=== ProblemDiagnoser ===")
        listOf(
            "tilted horizon      " to metrics(tiltDeg = 6.5f, brightness = 0.48f, variance = 180f, margin = 0.12f),
            "dark indoor shot    " to metrics(tiltDeg = 0.5f, brightness = 0.12f, variance = 140f, margin = 0.15f),
            "blown-out sky       " to metrics(tiltDeg = 1.0f, brightness = 0.93f, variance = 200f, margin = 0.10f),
            "motion blur         " to metrics(tiltDeg = 0.8f, brightness = 0.50f, variance = 12f, margin = 0.11f),
            "too much empty space" to metrics(tiltDeg = 0.4f, brightness = 0.55f, variance = 190f, margin = 0.38f),
            "backlit subject     " to metrics(tiltDeg = 0.3f, brightness = 0.44f, variance = 160f, margin = 0.14f, backlight = 2.4f),
        ).forEach { (label, imageMetrics) ->
            val problems = ProblemDiagnoser().diagnose(imageMetrics)
            println("  $label → " + if (problems.isEmpty()) "none" else problems.joinToString {
                "${it.code}(${it.severity}, value=%.2f)".format(it.value)
            })
        }
    }

    private fun metrics(
        tiltDeg: Float,
        brightness: Float,
        variance: Float,
        margin: Float,
        backlight: Float? = null,
    ) = ImageMetrics(
        tiltDeg = tiltDeg,
        brightnessMean = brightness,
        laplacianVariance = variance,
        leftMargin = margin,
        rightMargin = margin,
        backlightRatio = backlight,
    )

    private fun NormalizedBox.pretty() = "(%.2f, %.2f, %.2f, %.2f)".format(left, top, right, bottom)

    /**
     * Best-effort projection of a preset into the card feature space ProfileEngine
     * compares against. There is no agreed mapping yet: `framing`, `brightness`,
     * `sharpness` and `candidness` have no preset counterpart, so they are derived
     * from the nearest available parameter and marked here rather than in main code.
     */
    private fun StylePreset.toPresetProfileBestEffort(): PresetProfile {
        fun mid(pair: List<Double>) = ((pair[0] + pair[1]) / 2.0).toFloat()
        return PresetProfile(
            id = id,
            composition = mapOf(
                "subjectScale" to mid(composition.subjectScaleRange),
                "subjectPosition" to when (composition.subjectPosition) {
                    "third_left" -> 1f / 3f
                    "third_right" -> 2f / 3f
                    else -> 0.5f
                },
                "headroom" to mid(composition.headroomRange),
                "backgroundRatio" to mid(composition.backgroundRatio),
                "framing" to (1f - cropFreedom.toFloat()), // no preset field for framing
            ),
            color = mapOf(
                "brightness" to (0.5f + color.exposureBias.toFloat()).coerceIn(0f, 1f), // derived from exposureBias
                "colorTemperature" to color.colorTemperature.toFloat(),
                "saturation" to (0.5f + color.saturation.toFloat()).coerceIn(0f, 1f),
                "contrast" to (0.5f + color.contrast.toFloat()).coerceIn(0f, 1f),
                "sharpness" to (1f - color.blurStrength.toFloat()).coerceIn(0f, 1f), // no sharpness field
                "grain" to color.grain.toFloat(),
                "candidness" to when (composition.posePattern) { // rough proxy
                    "candid_motion" -> 0.85f
                    "natural_standing" -> 0.6f
                    else -> 0.4f
                },
            ),
        )
    }
}
