package com.gamdo.app.guide

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideConfigJsonTest {
    @Test
    fun `config json maps all tuning values into engine config`() {
        // v1 shape: tuning keys at the top level. Still accepted so an older asset
        // (or a device with a stale APK's config pushed onto it) keeps working.
        val config = parseGuideConfig(
            """
            {
              "version": 1,
              "smoothingWindow": 7,
              "alignedIouThreshold": 0.65,
              "recomputeMovementThreshold": 0.12,
              "minPoseConfidence": 0.4,
              "maxUnstableFrames": 4
            }
            """.trimIndent(),
        )

        assertEquals(7, config.smoothingWindow)
        assertEquals(0.65f, config.alignedIouThreshold, 0.0001f)
        assertEquals(0.12f, config.recomputeMovementThreshold, 0.0001f)
        assertEquals(0.4f, config.minPoseConfidence, 0.0001f)
        assertEquals(4, config.maxUnstableFrames)
    }

    @Test
    fun `namespaced config populates every block`() {
        val bundle = parseGuideConfigBundle(
            """
            {
              "version": 2,
              "alignment": {
                "smoothingWindow": 9,
                "alignedIouThreshold": 0.55,
                "overlayStabilizer": {
                  "alignedEnterFrames": 4,
                  "alignedExitFrames": 8,
                  "silhouetteHoldFrames": 2,
                  "visibleHoldFrames": 3,
                  "maxStepPerFrameNorm": 0.02
                }
              },
              "features": { "lowLightThreshold": 0.25, "analysisBudgetMs": 20.0 },
              "diagnoser": { "tiltDegrees": 5.0 },
              "stability": { "sequenceFps": 15, "sequenceSeconds": 30, "minStableFrames": 8 }
            }
            """.trimIndent(),
        )

        assertEquals(9, bundle.toGuideConfig().smoothingWindow)
        assertEquals(0.55f, bundle.toGuideConfig().alignedIouThreshold, 0.0001f)

        val stabilizer = bundle.toStabilizerConfig()
        assertEquals(4, stabilizer.alignedEnterFrames)
        assertEquals(8, stabilizer.alignedExitFrames)
        assertEquals(2, stabilizer.silhouetteHoldFrames)
        assertEquals(3, stabilizer.visibleHoldFrames)
        assertEquals(0.02f, stabilizer.maxStepPerFrameNorm, 0.0001f)

        assertEquals(20.0, bundle.features.analysisBudgetMs, 0.0001)
        assertEquals(0.25f, bundle.features.lowLightThreshold, 0.0001f)
        assertEquals(5f, bundle.toDiagnoserConfig().tiltDegrees, 0.0001f)
        assertEquals(450, bundle.stability.sequenceFrames)
        assertEquals(8, bundle.stability.minStableFrames)
    }

    @Test
    fun `unknown keys are ignored so adding one stays backward compatible`() {
        val bundle = parseGuideConfigBundle(
            """
            {
              "version": 99,
              "alignment": { "smoothingWindow": 3, "somethingNewFromB": 1 },
              "aFutureNamespace": { "x": 1 }
            }
            """.trimIndent(),
        )

        assertEquals(3, bundle.toGuideConfig().smoothingWindow)
        // Absent blocks fall back to their defaults rather than failing the parse.
        assertEquals(30.0, bundle.features.analysisBudgetMs, 0.0001)
    }

    @Test
    fun `the shipped asset parses and stays inside the engine's accepted ranges`() {
        // CFG-1: this asset is the source of truth, so a typo in it must fail here
        // rather than silently fall back to code defaults on a device.
        val bundle = parseGuideConfigBundle(readAsset("guide_config.json"))

        assertEquals(3, bundle.version)
        val engine = bundle.toGuideConfig() // constructor `require`s the ranges
        assertTrue(engine.smoothingWindow > 0)
        assertTrue(engine.alignedIouThreshold in 0f..1f)
        bundle.toStabilizerConfig() // ditto
        assertTrue(bundle.features.analysisBudgetMs > 0.0)
        assertTrue(bundle.stability.sequenceFrames > 0)
        assertTrue(bundle.stability.minVisibleRatio in 0f..1f)
        val objectGuide = bundle.toObjectTrackerConfig()
        assertEquals(5, objectGuide.windowSize)
        assertEquals(3, objectGuide.confirmationsRequired)
        assertEquals(4, objectGuide.maxObjects)
        assertEquals(0.80f, objectGuide.semanticMinConfidence, 0.0001f)
        val shape = bundle.objectGuide.toDetectedSlotShapeConfig()
        assertEquals(0.55f, shape.aspectRatioMin, 0.0001f)
        assertEquals(1.80f, shape.aspectRatioMax, 0.0001f)
        assertEquals(0.70f, shape.scaleMin, 0.0001f)
        assertEquals(1.16f, shape.scaleMax, 0.0001f)
    }

    @Test
    fun `asset thresholds and not code defaults drive the engine`() {
        // Guards the CFG-1 direction of dependency: if someone reverts the asset to
        // a shape the parser no longer reads, every value would quietly become the
        // code default and this test would be the only thing that notices.
        val bundle = parseGuideConfigBundle(
            readAsset("guide_config.json").replace("\"smoothingWindow\": 5", "\"smoothingWindow\": 11"),
        )

        assertEquals(11, bundle.toGuideConfig().smoothingWindow)
    }

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

    @Test
    fun `projection keeps visual fields and excludes internal metrics`() {
        val state = OverlayState(
            targetFrame = RectN(0.1f, 0.2f, 0.8f, 0.9f),
            silhouette = SilhouetteSpec(RectN(0.2f, 0.3f, 0.7f, 0.8f)),
            horizonY = 0.5f,
            visible = true,
            aligned = true,
        )

        val projection = state.toProjection()

        assertEquals(state.targetFrame, projection.targetFrame)
        assertEquals(state.silhouette?.bounds, projection.silhouetteBounds)
        assertTrue(projection.visible)
        assertTrue(projection.aligned)
    }
}
