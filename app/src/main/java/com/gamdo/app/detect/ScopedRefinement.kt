package com.gamdo.app.detect

import android.graphics.Bitmap
import com.gamdo.app.guide.PointN
import com.gamdo.app.guide.ScenePolygonRegion
import kotlin.math.ceil
import kotlin.math.floor

data class ScopeCrop(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}

object ScopeCropResolver {
    fun forPolygon(polygon: ScenePolygonRegion, padding: Float = .03f): ScopeCrop {
        val left = polygon.points.minOf { it.x }; val top = polygon.points.minOf { it.y }
        val right = polygon.points.maxOf { it.x }; val bottom = polygon.points.maxOf { it.y }
        return ScopeCrop((left-padding).coerceIn(0f,1f), (top-padding).coerceIn(0f,1f),
            (right+padding).coerceIn(0f,1f), (bottom+padding).coerceIn(0f,1f))
    }
}

class PolygonBitmapMasker {
    fun maskOutsidePolygon(bitmap: Bitmap, polygon: ScenePolygonRegion, crop: ScopeCrop): Bitmap {
        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(bitmap.width * bitmap.height); bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val nx = crop.left + (x + .5f) / bitmap.width * crop.width
            val ny = crop.top + (y + .5f) / bitmap.height * crop.height
            if (!polygon.contains(PointN(nx, ny))) pixels[y * bitmap.width + x] = 0xFF808080.toInt()
        }
        out.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return out
    }
}

/** Conservative mask split: only disconnected components are separated. */
class MaskInstanceSplitter(private val minimumPartRatio: Float = .15f, private val maxParts: Int = 4) {
    fun split(mask: CompactConfidenceMask, threshold: Float = .55f): List<CompactConfidenceMask> {
        val w = mask.width; val h = mask.height; val seen = BooleanArray(w*h); val parts = mutableListOf<List<Int>>()
        fun visit(start: Int): List<Int> { val q = ArrayDeque<Int>(); val part = mutableListOf<Int>(); q += start; seen[start] = true
            while (q.isNotEmpty()) { val p=q.removeFirst(); part += p; val x=p%w; val y=p/w
                for (n in intArrayOf(if(x>0)p-1 else -1, if(x<w-1)p+1 else -1, if(y>0)p-w else -1, if(y<h-1)p+w else -1)) if(n>=0&&!seen[n]&&mask.values[n]>=threshold){seen[n]=true;q+=n} }
            return part }
        for(i in mask.values.indices) if(!seen[i]&&mask.values[i]>=threshold) parts += visit(i)
        val minSize=(w*h*minimumPartRatio).toInt().coerceAtLeast(1)
        if (parts.size <= 1) {
            val component = parts.firstOrNull().orEmpty()
            val seeds = distancePeaks(component, mask)
            if (seeds.size >= 2) {
                val split = seeds.map { seed -> component.sortedBy { distance(it, seed, w) }.take(component.size / seeds.size + 1) }.flatten().distinct()
                val groups = seeds.map { seed -> split.filter { pixel ->
                    seeds.indices.minByOrNull { distance(pixel, seeds[it], w) } == seeds.indexOf(seed)
                } }
                if (groups.size >= 2 && groups.all { it.size >= minSize }) {
                    return groups.take(maxParts).map { pixels ->
                        val values = FloatArray(w * h); pixels.forEach { values[it] = mask.values[it] }; mask.copy(values = values)
                    }
                }
            }
            return listOf(mask)
        }
        if(parts.none { it.size>=minSize }) return listOf(mask)
        return parts.filter { it.size>=minSize }.sortedByDescending { it.size }.take(maxParts).map { pixels ->
            val values=FloatArray(w*h); pixels.forEach { values[it]=mask.values[it] }; mask.copy(values=values)
        }
    }

    /** Chamfer-style distance transform peaks. Peaks must be six cells apart,
     * preventing texture noise from becoming fake objects. */
    private fun distancePeaks(component: List<Int>, mask: CompactConfidenceMask): List<Int> {
        val w = mask.width
        val inside = component.toHashSet()
        val distances = component.associateWith { pixel ->
            val x = pixel % w; val y = pixel / w
            minOf(x, w - 1 - x, y, mask.height - 1 - y).toFloat()
        }
        return component.sortedByDescending { distances[it] ?: 0f }.filter { candidate ->
            (distances[candidate] ?: 0f) >= 3f &&
                distances.keys.none { it != candidate && distances[it]!! >= distances[candidate]!! && distance(it, candidate, w) < 6 }
        }.take(maxParts)
    }

    private fun distance(a: Int, b: Int, width: Int): Int {
        val ax = a % width; val ay = a / width; val bx = b % width; val by = b / width
        return kotlin.math.abs(ax - bx) + kotlin.math.abs(ay - by)
    }
}

/** Coordinates explicit polygon re-analysis with the same track manager used by
 * the live detector. A stale result is rejected when the scope revision changed. */
class ScopedRefinementWorker(
    private val detector: EfficientDetSceneDetector,
    private val scopeStore: SceneSearchScopeStore,
    private val tracks: ObjectTrackManager,
) {
    fun refine(frame: AnalysisFrame): List<TrackedSceneObject> {
        val scope = scopeStore.current() as? DetectionSearchScope.Polygon ?: return emptyList()
        val result = detector.detectPolygon(frame, scope.region, scope.revision)
        if (!result.ran || result.scopeRevision != scope.revision) return emptyList()
        val candidates = result.objects.map { observation ->
            SceneObjectCandidate(
                box = observation.box,
                detectionConfidence = observation.detectionConfidence ?: observation.confidence,
                category = observation.category,
                classificationConfidence = observation.classificationConfidence,
                source = DetectionSource.SCOPE_CROP,
            )
        }
        return tracks.update(result.scopeRevision, candidates)
            .filter { scope.region.accepts(it.box, minimumBoxOverlap = .50f) }
            .take(4)
    }
}
