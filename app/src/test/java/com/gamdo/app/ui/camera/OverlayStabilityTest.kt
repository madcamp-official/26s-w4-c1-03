package com.gamdo.app.ui.camera

import com.gamdo.app.guide.OverlayStabilizerConfig
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §0.4 go/no-go evidence for the guide overlay.
 *
 * The lead judges Day 3 stability; this test only produces the numbers. It runs
 * the synthetic one-minute shoot twice through the same production path — once
 * with the display stabilizer configured as an identity function (the "before"
 * baseline) and once with the shipped `guide_config.json` values — and writes
 * both to `app/build/reports/overlay-stability/report.txt`.
 *
 * Pass bars come from `stability` in the asset (CFG-1), never from this file.
 */
class OverlayStabilityTest {

    private val bundle = OverlayStabilityHarness.loadBundle()

    @Test
    fun `one minute of shooting produces no overlay flicker or coordinate jump`() {
        val baseline = OverlayStabilityHarness.run(
            bundle,
            stabilizerOverride = OverlayStabilizerConfig.PassThrough,
        )
        val stabilized = OverlayStabilityHarness.run(bundle)

        val before = OverlayStabilityHarness.report(baseline, bundle)
        val after = OverlayStabilityHarness.report(stabilized, bundle)

        val text = buildString {
            appendLine("§0.4 오버레이 안정성 측정 — 합성 1분 시퀀스, 프로덕션 축소 경로")
            appendLine()
            append(OverlayStabilityHarness.render(before, "BEFORE (stabilizer = PassThrough)", bundle))
            appendLine()
            append(OverlayStabilityHarness.render(after, "AFTER  (guide_config.json 적용)", bundle))
        }
        println(text)
        writeArtifact(text)

        val s = bundle.stability

        // F1~F3 — nothing may blink.
        assertEquals("visible 깜빡임\n$text", 0, after.visibleFlickers)
        assertEquals("silhouette 깜빡임\n$text", 0, after.silhouetteFlickers)
        assertEquals("aligned 깜빡임\n$text", 0, after.alignedFlickers)

        // J1/J2 — no rect edge may move faster than the configured limit.
        assertTrue(
            "targetFrame 좌표 튐\n$text",
            after.maxTargetFrameDelta <= s.maxFrameDeltaNorm + 1e-4f,
        )
        assertTrue(
            "silhouette 좌표 튐\n$text",
            after.maxSilhouetteDelta <= s.maxFrameDeltaNorm + 1e-4f,
        )

        // J3 — AlignmentEngine's "hold the last stable value" must survive the
        // stabilizer rather than be re-derived by it.
        assertEquals(
            "신뢰도 급락 구간 유지\n$text",
            after.dropSegmentFrames,
            after.heldFramesDuringDrop,
        )

        // J4 — anti-cheat: hiding the overlay would satisfy F1~F3 trivially.
        assertTrue(
            "인물 존재 구간 표시율\n$text",
            after.visibleRatioWhilePresent >= s.minVisibleRatio,
        )
    }

    @Test
    fun `the unstabilized path is measurably worse, so the harness can detect regressions`() {
        val baseline = OverlayStabilityHarness.report(
            OverlayStabilityHarness.run(bundle, OverlayStabilizerConfig.PassThrough),
            bundle,
        )

        // If this ever reaches zero the scenario has stopped reproducing the
        // failure mode and the passing "after" number means nothing.
        assertTrue(
            "합성 시퀀스가 더 이상 깜빡임을 재현하지 못함: $baseline",
            baseline.silhouetteFlickers + baseline.alignedFlickers > 0,
        )
    }

    @Test
    fun `the target bracket is a function of the preset, not of the subject`() {
        // Why J1/J2 read 0.00000, and it is not the harness failing to look:
        // `AlignmentEngine.targetFrame()` derives the bracket from `StyleTarget`
        // alone, so no amount of subject movement can make it jump. "좌표 튐" is
        // structurally impossible today and the whole §0.4 risk sits in the two
        // booleans. The day this fails, the bracket has started tracking the
        // subject and J1/J2 have become live measurements that need re-reading.
        val distinct = OverlayStabilityHarness.run(bundle)
            .mapNotNull { it.targetFrame }
            .distinct()

        assertEquals("브래킷 좌표는 프리셋당 1종이어야 한다: $distinct", 1, distinct.size)
    }

    @Test
    fun `low light dropouts are the dominant flicker source before stabilization`() {
        val baseline = OverlayStabilityHarness.report(
            OverlayStabilityHarness.run(bundle, OverlayStabilizerConfig.PassThrough),
            bundle,
        )

        assertTrue(
            "구간별 깜빡임이 비어 있음: $baseline",
            baseline.perSegmentFlickers.isNotEmpty(),
        )
        assertTrue(
            "저조도/경계 구간이 최악이 아님: ${baseline.perSegmentFlickers}",
            baseline.worstSegment == OverlayStabilityHarness.Segment.LOW_LIGHT.label ||
                baseline.worstSegment == OverlayStabilityHarness.Segment.BOUNDARY_DITHER.label,
        )
    }

    private fun writeArtifact(text: String) {
        runCatching {
            val dir = File(System.getProperty("user.dir") ?: ".", "build/reports/overlay-stability")
            dir.mkdirs()
            File(dir, "report.txt").writeText(text)
        }
    }
}
