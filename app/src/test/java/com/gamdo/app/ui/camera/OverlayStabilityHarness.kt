package com.gamdo.app.ui.camera

import com.gamdo.app.camera.TiltReading
import com.gamdo.app.detect.BrightnessSample
import com.gamdo.app.detect.DetectionResult
import com.gamdo.app.detect.FaceObservation
import com.gamdo.app.detect.NormalizedBox
import com.gamdo.app.detect.PoseLandmarkPoint
import com.gamdo.app.detect.PoseObservation
import com.gamdo.app.guide.GuideConfigBundle
import com.gamdo.app.guide.OverlayStabilizerConfig
import com.gamdo.app.guide.RectN
import com.gamdo.app.guide.StyleTarget
import com.gamdo.app.guide.parseGuideConfigBundle
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * §0.4 overlay-stability harness.
 *
 * With no test device available, the §3-2 completion criterion ("1분 연속 관찰에서
 * 오버레이 깜빡임·좌표 튐 없음") cannot be judged by eye, so it is restated as
 * counted events over a synthetic one-minute shoot and measured here.
 *
 * The harness drives the **real production reduction path** — it feeds
 * [CameraViewModel.onFrameAnalyzed] and reads [CameraViewModel.overlay], the exact
 * `StateFlow` Compose collects — so `FrameFeatureCalculator → AlignmentEngine →
 * OverlayStabilizer → OverlayData` all run for real. There is deliberately no
 * simulator: a parallel implementation would measure itself, not the app. What is
 * *not* covered is ML Kit detection quality and Canvas rendering; those stay on
 * the DONE-DEVICE list.
 *
 * Every threshold comes from `assets/guide_config.json` (CFG-1).
 */
internal object OverlayStabilityHarness {

    // ---------------------------------------------------------------- config

    /** Reads the shipped asset, so the harness judges the same numbers the app runs. */
    fun loadBundle(): GuideConfigBundle = parseGuideConfigBundle(readAsset("guide_config.json"))

    private fun readAsset(name: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (candidate in listOf("src/main/assets/$name", "app/src/main/assets/$name")) {
                val file = File(dir, candidate)
                if (file.isFile) return file.readText()
            }
            dir = dir.parentFile
        }
        error("assets/$name not found from ${System.getProperty("user.dir")}")
    }

    // -------------------------------------------------------------- scenario

    /**
     * One phase of the synthetic shoot. [personPresent] means "a person is in
     * front of the lens", which is not the same as "the detector saw one this
     * frame" — that distinction is what the dropout segments exercise.
     */
    enum class Segment(val label: String, val personPresent: Boolean) {
        COLD_START("빈 장면(콜드스타트)", false),
        ENTER("인물 진입", true),
        HANDHELD("정지 + 손떨림", true),
        CONFIDENCE_DROP("신뢰도 급락(인물 미검출)", false),
        APPROACH("목표로 접근", true),
        BOUNDARY_DITHER("정렬 경계 진동", true),
        LOW_LIGHT("저조도 + 간헐 미검출", true),
        EXIT("인물 이탈", true),
        EMPTY("빈 장면", false),
    }

    /**
     * Segment lengths in seconds; scaled by `stability.sequenceFps` to frames.
     *
     * The order is a continuous story — walk in, settle into the target, stand,
     * turn away, drift back to the edge, lose the light, walk out — so the
     * subject's offset never teleports across a segment boundary. A discontinuity
     * there would show up as a jump the app can never actually be shown.
     */
    private val SEGMENT_SECONDS = listOf(
        Segment.COLD_START to 5,
        Segment.ENTER to 5,
        Segment.APPROACH to 10,
        Segment.HANDHELD to 15,
        Segment.CONFIDENCE_DROP to 5,
        Segment.BOUNDARY_DITHER to 5,
        Segment.LOW_LIGHT to 5,
        Segment.EXIT to 5,
        Segment.EMPTY to 5,
    )

    /** Frames a segment takes to ease into its holding offset from the previous one. */
    private const val SETTLE_FRAMES = 12f

    /**
     * Horizontal offset from the target frame at which IoU equals the default
     * `alignedIouThreshold` of 0.7, for a person box the same size as the target
     * (target 0.36 × 0.45): intersection (0.36 − dx)·0.45 over union 0.324 −
     * intersection = 0.7 ⇒ dx ≈ 0.0635. The dither segment straddles this.
     */
    private const val ALIGN_EDGE_DX = 0.0635f

    data class SyntheticFrame(
        val index: Int,
        val segment: Segment,
        val detection: DetectionResult,
        val tilt: TiltReading,
        val brightness: BrightnessSample,
        val shake: Float,
        /** Raw person box the detector reported, for reference-jitter measurement. */
        val personBox: NormalizedBox?,
    )

    fun sequence(bundle: GuideConfigBundle, target: StyleTarget = StyleTarget()): List<SyntheticFrame> {
        val fps = bundle.stability.sequenceFps
        val base = targetRect(target)
        val frames = mutableListOf<SyntheticFrame>()
        var index = 0
        for ((segment, seconds) in SEGMENT_SECONDS) {
            val length = seconds * fps
            for (i in 0 until length) {
                frames += frameFor(segment, i, length, index, base)
                index++
            }
        }
        return frames
    }

    /** Mirrors `AlignmentEngine.targetFrame` so the scenario can aim at it. */
    private fun targetRect(target: StyleTarget): RectN {
        val height = ((target.subjectScaleRange.start + target.subjectScaleRange.endInclusive) / 2f)
            .coerceIn(0.12f, 0.92f)
        val width = (height * target.targetAspectRatio).coerceIn(0.12f, 0.92f)
        val centerX = target.subjectAnchorX.coerceIn(width / 2f, 1f - width / 2f)
        val headroom = ((target.headroomRange.start + target.headroomRange.endInclusive) / 2f)
            .coerceIn(0f, 0.8f)
        val centerY = (headroom + height / 2f).coerceIn(height / 2f, 1f - height / 2f)
        return RectN(
            centerX - width / 2f,
            centerY - height / 2f,
            centerX + width / 2f,
            centerY + height / 2f,
        )
    }

    private fun frameFor(
        segment: Segment,
        i: Int,
        length: Int,
        index: Int,
        base: RectN,
    ): SyntheticFrame {
        val t = if (length <= 1) 0f else i.toFloat() / (length - 1)
        val normalLight = BrightnessSample(frameMean = 0.52f)
        // A little roll all the way through: the horizon is sensor-driven and must
        // not be able to disturb the guide path.
        val tilt = TiltReading(rollDeg = 1.2f * sin(index * 0.11f), pitchDeg = 12f)

        fun person(dx: Float, dy: Float = 0f, confidence: Float, shake: Float, light: BrightnessSample): SyntheticFrame {
            val box = base.shifted(dx, dy).clamped()
            return SyntheticFrame(
                index = index,
                segment = segment,
                detection = detectionOf(box, confidence),
                tilt = tilt,
                brightness = light,
                shake = shake,
                personBox = box.toBox(),
            )
        }

        fun nobody(light: BrightnessSample, shake: Float = 0.01f) = SyntheticFrame(
            index = index,
            segment = segment,
            detection = DetectionResult(faces = emptyList(), pose = null),
            tilt = tilt,
            brightness = light,
            shake = shake,
            personBox = null,
        )

        return when (segment) {
            Segment.COLD_START, Segment.EMPTY -> nobody(normalLight)

            // Walks in from the left; pose confidence ramps through the usable floor.
            Segment.ENTER -> person(
                dx = -0.34f + 0.54f * t,
                confidence = 0.15f + 0.75f * t,
                shake = 0.03f,
                light = normalLight,
            )

            // Closes in on the target; IoU crosses the aligned threshold once.
            Segment.APPROACH -> person(
                dx = 0.20f - 0.20f * t,
                confidence = 0.85f,
                shake = 0.015f,
                light = normalLight,
            )

            // Standing still, handheld: ±0.004 tremor on a 3-frame period.
            Segment.HANDHELD -> {
                val tremor = 0.004f * sin(i * (2f * Math.PI.toFloat() / 3f))
                person(
                    dx = tremor,
                    dy = tremor * 0.6f,
                    confidence = 0.88f + 0.03f * sin(i * 0.7f),
                    shake = 0.02f,
                    light = normalLight,
                )
            }

            // Subject turns away / is occluded: no landmarks clear the filter and
            // no face is found, so personBox goes null for the whole segment.
            Segment.CONFIDENCE_DROP -> nobody(normalLight, shake = 0.02f)

            // Drifts back out to the IoU threshold and oscillates across it every
            // two frames — the worst case for the alignment colour cue.
            Segment.BOUNDARY_DITHER -> person(
                dx = ALIGN_EDGE_DX * settle(i) + 0.006f * sin(i * (Math.PI.toFloat() / 2f)),
                confidence = 0.85f,
                shake = 0.02f,
                light = normalLight,
            )

            // Dark scene: the detector misses every third frame.
            Segment.LOW_LIGHT -> {
                val dark = BrightnessSample(frameMean = 0.10f)
                val dx = ALIGN_EDGE_DX + (0.02f - ALIGN_EDGE_DX) * settle(i)
                if (i % 3 == 2) {
                    nobody(dark, shake = 0.05f)
                } else {
                    person(dx = dx, confidence = 0.62f, shake = 0.05f, light = dark)
                }
            }

            Segment.EXIT -> person(
                dx = 0.02f + 0.53f * t,
                confidence = 0.85f - 0.65f * t,
                shake = 0.03f,
                light = normalLight,
            )
        }
    }

    /**
     * Builds a detector result whose pose bounding box is exactly [box].
     *
     * Landmark likelihood tracks [confidence] so that a genuine confidence
     * collapse also removes the box — that is how ML Kit behaves, and separating
     * the two would hide the very dropout the harness is looking for.
     */
    private fun detectionOf(box: RectN, confidence: Float): DetectionResult {
        val c = confidence.coerceIn(0f, 1f)
        val clamped = box.clamped()
        val landmarkLikelihood = if (c >= 0.3f) max(c, 0.31f) else c * 0.5f
        val landmarks = listOf(
            clamped.left to clamped.top,
            clamped.right to clamped.top,
            clamped.left to clamped.bottom,
            clamped.right to clamped.bottom,
            (clamped.left + clamped.right) / 2f to (clamped.top + clamped.bottom) / 2f,
        ).mapIndexed { type, (x, y) ->
            PoseLandmarkPoint(type = type, x = x, y = y, inFrameLikelihood = landmarkLikelihood)
        }
        // Head roughly in the top fifth of the body box.
        val faceHeight = clamped.height * 0.2f
        val faceWidth = clamped.width * 0.45f
        val faceCenterX = (clamped.left + clamped.right) / 2f
        val face = FaceObservation(
            box = NormalizedBox(
                left = faceCenterX - faceWidth / 2f,
                top = clamped.top,
                right = faceCenterX + faceWidth / 2f,
                bottom = clamped.top + faceHeight,
            ),
            leftEyeOpenProbability = 0.9f,
            rightEyeOpenProbability = 0.9f,
            headEulerAngleZ = 0f,
        )
        return DetectionResult(
            faces = if (c >= 0.3f) listOf(face) else emptyList(),
            pose = PoseObservation(landmarks = landmarks, averageInFrameLikelihood = c),
        )
    }

    /** 0→1 ease used to walk into a segment's holding offset instead of jumping to it. */
    private fun settle(i: Int): Float = (i / SETTLE_FRAMES).coerceAtMost(1f)

    private fun RectN.shifted(dx: Float, dy: Float = 0f) =
        RectN(left + dx, top + dy, right + dx, bottom + dy)

    private fun RectN.toBox() = NormalizedBox(left, top, right, bottom)

    // --------------------------------------------------------------- running

    /** One published overlay state, as Compose would have received it. */
    data class Observation(
        val index: Int,
        val segment: Segment,
        val visible: Boolean,
        val aligned: Boolean,
        val hasSilhouette: Boolean,
        val targetFrame: RectN?,
        val silhouette: RectN?,
        val personBox: NormalizedBox?,
    )

    /**
     * Runs the sequence through a real [CameraViewModel].
     *
     * [stabilizerOverride] swaps the display damping without changing anything
     * else, so the "before" measurement is the same code path with the stabilizer
     * configured as an identity function — not a second implementation.
     */
    fun run(
        bundle: GuideConfigBundle,
        stabilizerOverride: OverlayStabilizerConfig? = null,
        target: StyleTarget = StyleTarget(),
    ): List<Observation> {
        val effective = stabilizerOverride?.let { override ->
            bundle.copy(
                alignment = bundle.alignment.copy(
                    overlayStabilizer = bundle.alignment.overlayStabilizer.copy(
                        alignedEnterFrames = override.alignedEnterFrames,
                        alignedExitFrames = override.alignedExitFrames,
                        silhouetteHoldFrames = override.silhouetteHoldFrames,
                        visibleHoldFrames = override.visibleHoldFrames,
                        maxStepPerFrameNorm = override.maxStepPerFrameNorm,
                    ),
                ),
            )
        } ?: bundle

        val viewModel = CameraViewModel(config = effective, collectDebugSignals = false)
        viewModel.setStyleTarget(target)

        return sequence(bundle, target).map { frame ->
            viewModel.onFrameAnalyzed(
                detection = frame.detection,
                tilt = frame.tilt,
                brightness = frame.brightness,
                shake = frame.shake,
                frameWidth = 720,
                frameHeight = 960,
                mirror = false,
            )
            val guide = viewModel.overlay.value?.guide
            Observation(
                index = frame.index,
                segment = frame.segment,
                visible = guide?.visible == true,
                aligned = guide?.aligned == true,
                hasSilhouette = guide?.silhouetteBounds != null,
                targetFrame = guide?.targetFrame,
                silhouette = guide?.silhouetteBounds,
                personBox = frame.personBox,
            )
        }
    }

    // --------------------------------------------------------------- metrics

    /**
     * The §3-2 criterion turned into numbers.
     *
     * Flicker is counted with a **dwell rule** rather than as raw transitions: a
     * person genuinely walking out should hide the silhouette and it should stay
     * hidden — that is correct behaviour, not a blink. What the eye reads as a
     * blink is a state that flips and flips back before it settles, so a
     * transition counts only when the state it enters survives fewer than
     * `stability.minStableFrames` frames. The final run is excluded: the sequence
     * ends before we can know whether it would have reverted.
     */
    data class Report(
        val frames: Int,
        val visibleFlickers: Int,
        val alignedFlickers: Int,
        val silhouetteFlickers: Int,
        val visibleTransitions: Int,
        val alignedTransitions: Int,
        val silhouetteTransitions: Int,
        val maxTargetFrameDelta: Float,
        val maxSilhouetteDelta: Float,
        val maxPersonBoxDelta: Float,
        val heldFramesDuringDrop: Int,
        val dropSegmentFrames: Int,
        val visibleRatioWhilePresent: Float,
        val worstSegment: String,
        val perSegmentFlickers: Map<String, Int>,
    )

    fun report(observations: List<Observation>, bundle: GuideConfigBundle): Report {
        val dwell = bundle.stability.minStableFrames
        val visibleFlickers = flickerIndices(observations.map { it.visible }, dwell)
        val alignedFlickers = flickerIndices(observations.map { it.aligned }, dwell)
        val silhouetteFlickers = flickerIndices(observations.map { it.hasSilhouette }, dwell)
        val allFlickers = visibleFlickers + alignedFlickers + silhouetteFlickers

        val perSegment = allFlickers
            .groupingBy { observations[it].segment.label }
            .eachCount()

        val dropIndices = observations.withIndex()
            .filter { it.value.segment == Segment.CONFIDENCE_DROP }
            .map { it.index }
        val preDropFrame = dropIndices.firstOrNull()
            ?.let { observations.getOrNull(it - 1)?.targetFrame }
        val held = dropIndices.count { observations[it].targetFrame == preDropFrame }

        val present = observations.filter { it.segment.personPresent }

        return Report(
            frames = observations.size,
            visibleFlickers = visibleFlickers.size,
            alignedFlickers = alignedFlickers.size,
            silhouetteFlickers = silhouetteFlickers.size,
            visibleTransitions = transitions(observations.map { it.visible }),
            alignedTransitions = transitions(observations.map { it.aligned }),
            silhouetteTransitions = transitions(observations.map { it.hasSilhouette }),
            maxTargetFrameDelta = maxRectDelta(observations.map { it.targetFrame }),
            maxSilhouetteDelta = maxRectDelta(observations.map { it.silhouette }),
            maxPersonBoxDelta = maxRectDelta(
                observations.map { obs -> obs.personBox?.let { RectN(it.left, it.top, it.right, it.bottom) } },
            ),
            heldFramesDuringDrop = held,
            dropSegmentFrames = dropIndices.size,
            visibleRatioWhilePresent =
            if (present.isEmpty()) 1f else present.count { it.visible }.toFloat() / present.size,
            worstSegment = perSegment.maxByOrNull { it.value }?.key ?: "없음",
            perSegmentFlickers = perSegment,
        )
    }

    /** Indices at which a state change reverts within [dwell] frames. */
    private fun flickerIndices(series: List<Boolean>, dwell: Int): List<Int> {
        if (series.size < 2) return emptyList()
        val result = mutableListOf<Int>()
        var runStart = 0
        for (i in 1 until series.size) {
            if (series[i] == series[i - 1]) continue
            // series[runStart until i] just ended; it was entered at runStart.
            if (runStart > 0 && i - runStart < dwell) result += runStart
            runStart = i
        }
        // The trailing run is truncated by the end of the sequence, so it is not
        // evidence of a revert.
        return result
    }

    private fun transitions(series: List<Boolean>): Int =
        (1 until series.size).count { series[it] != series[it - 1] }

    /**
     * Largest single-frame movement of any rect edge (Chebyshev, normalized).
     *
     * Chebyshev rather than the engine's own `hypot` distance: the eye tracks the
     * worst-moving edge, and reusing the engine's metric would make the criterion
     * a restatement of `recomputeMovementThreshold`.
     */
    private fun maxRectDelta(rects: List<RectN?>): Float {
        var worst = 0f
        for (i in 1 until rects.size) {
            val a = rects[i - 1] ?: continue
            val b = rects[i] ?: continue
            val delta = maxOf(
                abs(b.left - a.left),
                abs(b.top - a.top),
                abs(b.right - a.right),
                abs(b.bottom - a.bottom),
            )
            if (delta > worst) worst = delta
        }
        return worst
    }

    fun render(report: Report, title: String, bundle: GuideConfigBundle): String = buildString {
        val s = bundle.stability
        appendLine("── $title ──")
        appendLine(
            "  프레임 %d (%dfps × %d초) · 깜빡임 판정 dwell %d프레임".format(
                report.frames, s.sequenceFps, s.sequenceSeconds, s.minStableFrames,
            ),
        )
        appendLine(
            "  F1 visible    깜빡임 %3d / 전환 %3d".format(
                report.visibleFlickers, report.visibleTransitions,
            ),
        )
        appendLine(
            "  F2 silhouette 깜빡임 %3d / 전환 %3d".format(
                report.silhouetteFlickers, report.silhouetteTransitions,
            ),
        )
        appendLine(
            "  F3 aligned    깜빡임 %3d / 전환 %3d".format(
                report.alignedFlickers, report.alignedTransitions,
            ),
        )
        appendLine(
            "  J1 targetFrame 최대 프레임간 이동 %.5f (한계 %.5f)".format(
                report.maxTargetFrameDelta, s.maxFrameDeltaNorm,
            ),
        )
        appendLine(
            "  J2 silhouette  최대 프레임간 이동 %.5f (한계 %.5f)".format(
                report.maxSilhouetteDelta, s.maxFrameDeltaNorm,
            ),
        )
        appendLine(
            "  J3 신뢰도 급락 중 마지막 안정값 유지 %d / %d".format(
                report.heldFramesDuringDrop, report.dropSegmentFrames,
            ),
        )
        appendLine(
            "  J4 인물 존재 구간 표시율 %.3f (하한 %.3f)".format(
                report.visibleRatioWhilePresent, s.minVisibleRatio,
            ),
        )
        // Input motion, not overlay motion — no pass bar applies. It exists so a
        // "0 jump" result can be told apart from "nothing moved in the scenario".
        appendLine("  (입력) 피사체 personBox 최대 이동 %.5f".format(report.maxPersonBoxDelta))
        appendLine("  구간별 깜빡임: ${report.perSegmentFlickers.ifEmpty { mapOf("없음" to 0) }}")
    }
}
