package com.gamdo.app.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewGeometryTest {
    @Test
    fun `fill center round trip stays within one percent`() {
        val geometry = PreviewGeometry(1080, 2200, 1440, 1080, mirror = false)
        // A portrait full-bleed view crops the landscape analysis frame heavily;
        // only round-trip coordinates that are actually visible in the preview.
        val points = listOf(PointN(0.42f, 0.25f), PointN(0.5f, 0.5f), PointN(0.58f, 0.75f))
        points.forEach { point ->
            val view = geometry.analysisToView(point)
            val roundTrip = geometry.viewToAnalysis(view.first, view.second)!!
            assertEquals(point.x, roundTrip.x, 0.01f)
            assertEquals(point.y, roundTrip.y, 0.01f)
        }
    }

    @Test
    fun `front preview mirror is reversed only once`() {
        val geometry = PreviewGeometry(1000, 1000, 1000, 1000, mirror = true)
        assertEquals(0.8f, geometry.viewToAnalysis(200f, 500f)!!.x, 0.001f)
        assertEquals(200f, geometry.analysisToView(PointN(0.8f, 0.5f)).first, 0.001f)
    }

    @Test
    fun `safe insets transform canonical template`() {
        val geometry = PreviewGeometry(100, 200, 100, 200, safeInsets = InsetsN(0.1f, 0.2f, 0.1f, 0.1f))
        val safe = geometry.applySafeArea(RectN(0f, 0f, 1f, 1f))
        assertEquals(RectN(0.1f, 0.2f, 0.9f, 0.9f), safe)
    }

    @Test
    fun `polygon rejects too small selection and accepts intended boxes`() {
        assertNull(ScenePolygonRegion.fromNormalized(listOf(PointN(0f, 0f), PointN(0.01f, 0f), PointN(0.01f, 0.01f))))
        val polygon = ScenePolygonRegion.fromNormalized(listOf(
            PointN(0.2f, 0.2f), PointN(0.8f, 0.2f), PointN(0.8f, 0.8f), PointN(0.2f, 0.8f),
        ))!!
        assertTrue(polygon.accepts(com.gamdo.app.detect.NormalizedBox(0.3f, 0.3f, 0.5f, 0.5f)))
        assertTrue(!polygon.accepts(com.gamdo.app.detect.NormalizedBox(0.85f, 0.85f, 0.95f, 0.95f)))
    }
}
