package com.gamdo.app.guide

import com.gamdo.app.detect.NormalizedBox
import kotlin.math.abs

sealed interface SceneSearchScope {
    data object Default : SceneSearchScope
    data class Tap(val point: PointN) : SceneSearchScope
    data class Polygon(val points: List<PointN>) : SceneSearchScope
}

/** Validated lasso region in upright analysis coordinates. */
class ScenePolygonRegion private constructor(val points: List<PointN>) {
    val areaRatio: Float = polygonArea(points)

    fun accepts(box: NormalizedBox, minimumBoxOverlap: Float = 0.35f): Boolean {
        if (contains(PointN(box.centerX, box.centerY))) return true
        val boxArea = (box.width * box.height).coerceAtLeast(0.000001f)
        return approximateIntersection(box) / boxArea >= minimumBoxOverlap
    }

    fun contains(point: PointN): Boolean {
        var inside = false
        var previous = points.last()
        for (current in points) {
            val crosses = (current.y > point.y) != (previous.y > point.y)
            if (crosses) {
                val xAtY = (previous.x - current.x) * (point.y - current.y) /
                    (previous.y - current.y).coerceAtLeastMagnitude(0.000001f) + current.x
                if (point.x < xAtY) inside = !inside
            }
            previous = current
        }
        return inside
    }

    private fun approximateIntersection(box: NormalizedBox): Float {
        // Deterministic 12x12 sampling is sufficient for an inclusion policy and
        // avoids a geometry dependency in the on-device hot path.
        val samples = 12
        var accepted = 0
        for (iy in 0 until samples) for (ix in 0 until samples) {
            val x = box.left + box.width * (ix + 0.5f) / samples
            val y = box.top + box.height * (iy + 0.5f) / samples
            if (contains(PointN(x, y))) accepted++
        }
        return box.width * box.height * accepted / (samples * samples)
    }

    companion object {
        fun fromViewPath(points: List<Pair<Float, Float>>, geometry: PreviewGeometry): ScenePolygonRegion? {
            val mapped = points.mapNotNull { (x, y) -> geometry.viewToAnalysis(x, y) }
            return fromNormalized(mapped)
        }

        fun fromNormalized(points: List<PointN>): ScenePolygonRegion? {
            if (points.size < 3) return null
            val simplified = simplify(points.map(PointN::clamped), tolerance = 0.01f)
            if (simplified.size < 3) return null
            val area = polygonArea(simplified)
            if (area !in 0.02f..0.80f) return null
            return ScenePolygonRegion(simplified)
        }

        private fun simplify(points: List<PointN>, tolerance: Float): List<PointN> {
            if (points.size <= 3) return points
            val result = mutableListOf(points.first())
            points.drop(1).dropLast(1).forEach { point ->
                val last = result.last()
                if (abs(point.x - last.x) + abs(point.y - last.y) >= tolerance) result += point
            }
            result += points.last()
            return result
        }
    }
}

private fun polygonArea(points: List<PointN>): Float {
    if (points.size < 3) return 0f
    var sum = 0f
    points.indices.forEach { index ->
        val next = points[(index + 1) % points.size]
        sum += points[index].x * next.y - next.x * points[index].y
    }
    return abs(sum) / 2f
}

private fun Float.coerceAtLeastMagnitude(value: Float): Float = when {
    this >= 0f -> coerceAtLeast(value)
    else -> coerceAtMost(-value)
}
