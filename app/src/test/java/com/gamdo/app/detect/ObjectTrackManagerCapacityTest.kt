package com.gamdo.app.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The track table is unbounded; the matcher is not. This pins the seam between them.
 *
 * `MinimumCostMatcher` is a bit-mask dynamic program with a hard twelve-per-side
 * `require`, and `ObjectTrackManager.update` builds its cost matrix with one row per
 * *live track*. Nothing upstream limits how many tracks exist — every candidate that
 * fails to match opens one, and eviction waits out `maxMissedFrames` — so a scene with
 * a dozen-odd objects crossed the limit and the `require` threw out of the CameraX
 * analysis thread, taking the process with it. It reproduced on opening the 구도 sheet
 * because the pane resize rebinds the analyzer and every box moves at once.
 *
 * These tests are written against the *symptom a user sees* (the app stays up, the
 * table stays finite) rather than against the cap itself, so raising or lowering
 * `MATCHER_SIDE_LIMIT` does not make them fail for the wrong reason.
 */
class ObjectTrackManagerCapacityTest {

    private fun candidate(index: Int, frame: Int = 0): SceneObjectCandidate {
        // A grid of small, well-separated boxes: none of them can match another's
        // track, so each one opens a track of its own. Drifting by frame keeps the
        // boxes matching *themselves* across frames.
        val column = index % 6
        val row = index / 6
        val left = 0.02f + column * 0.16f + frame * 0.001f
        val top = 0.02f + row * 0.16f + frame * 0.001f
        return SceneObjectCandidate(
            box = NormalizedBox(left, top, left + 0.06f, top + 0.06f),
            detectionConfidence = 0.9f - index * 0.01f,
            category = GuideObjectCategory.UNKNOWN,
        )
    }

    @Test
    fun `a scene with more objects than the matcher's limit does not throw`() {
        val manager = ObjectTrackManager()
        // Twelve per frame is the candidate cap, so two frames of disjoint objects
        // is the shortest path to more than twelve live tracks.
        repeat(6) { frame ->
            manager.update(frame.toLong(), (0 until 12).map { candidate(it, frame) })
            manager.update(frame.toLong(), (12 until 24).map { candidate(it, frame) })
        }
        // Reaching here at all is the assertion: before the fix the second call threw
        // IllegalArgumentException from MinimumCostMatcher.
        assertTrue(manager.update(99L, (0 until 12).map { candidate(it, 6) }).isNotEmpty())
    }

    @Test
    fun `tracks outside the matched set still age out, so the table stays finite`() {
        val manager = ObjectTrackManager(V4ObjectTrackConfig(maxMissedFrames = 2))
        repeat(4) { frame ->
            manager.update(frame.toLong(), (0 until 12).map { candidate(it, frame) })
            manager.update(frame.toLong(), (12 until 24).map { candidate(it, frame) })
        }
        // Now feed only the first group for long enough that everything else must
        // pass `maxMissedFrames`. If misses were only incremented for the twelve
        // tracks handed to the matcher, the excluded ones would freeze below the
        // threshold and never be evicted.
        repeat(8) { frame ->
            manager.update((100 + frame).toLong(), (0 until 4).map { candidate(it, frame) })
        }
        val settled = manager.update(200L, (0 until 4).map { candidate(it, 8) })
        assertEquals(4, settled.size)
    }

    @Test
    fun `an empty frame does not disturb the matcher`() {
        val manager = ObjectTrackManager()
        manager.update(0L, (0 until 12).map { candidate(it) })
        // costs[0] is empty here, which the matcher short-circuits before its require.
        assertTrue(manager.update(1L, emptyList()).isNotEmpty())
    }
}
