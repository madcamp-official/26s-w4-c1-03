package com.gamdo.app.detect

import com.gamdo.app.guide.PointN
import com.gamdo.app.guide.ScenePolygonRegion
import org.junit.Assert.assertEquals
import org.junit.Test

class ScopedRefinementTest {
    @Test
    fun `objects joining on second and third ROI result are not dropped`() {
        val polygon = ScenePolygonRegion.fromNormalized(
            listOf(PointN(0.10f, 0.10f), PointN(0.90f, 0.10f), PointN(0.90f, 0.90f), PointN(0.10f, 0.90f)),
        )!!
        val scopeStore = SceneSearchScopeStore()
        scopeStore.setPolygon(polygon)
        val first = candidate(1, 0.15f)
        val second = candidate(2, 0.45f)
        val third = candidate(3, 0.72f)
        val detector = SequenceRefinementDetector(
            listOf(
                listOf(first),
                listOf(first, second),
                listOf(first, second, third),
            ),
        )
        val worker = ScopedRefinementWorker(detector, scopeStore, ObjectTrackManager())

        worker.refine(AnalysisFrame(null, 100, 100))
        worker.refine(AnalysisFrame(null, 100, 100))
        val fixed = worker.refine(AnalysisFrame(null, 100, 100))

        assertEquals(setOf(1L, 2L, 3L), fixed.map { it.trackId }.toSet())
        assertEquals(3, worker.refine(AnalysisFrame(null, 100, 100)).size)
    }

    private fun candidate(id: Int, x: Float): ObjectObservation = ObjectObservation(
        box = NormalizedBox(x, 0.35f, x + 0.12f, 0.58f),
        trackingId = id,
        category = GuideObjectCategory.UNKNOWN,
        detectionConfidence = 0.8f,
    )

    private class SequenceRefinementDetector(
        private val frames: List<List<ObjectObservation>>,
    ) : ScopedObjectRefinement {
        private var index = 0

        override fun detectPolygon(
            frame: AnalysisFrame,
            polygon: ScenePolygonRegion,
            scopeRevision: Long,
            padding: Float,
        ): EfficientDetSceneDetector.ScopedDetectionResult {
            val objects = frames[index.coerceAtMost(frames.lastIndex)]
            index++
            return EfficientDetSceneDetector.ScopedDetectionResult(objects, scopeRevision, ran = true)
        }
    }
}
