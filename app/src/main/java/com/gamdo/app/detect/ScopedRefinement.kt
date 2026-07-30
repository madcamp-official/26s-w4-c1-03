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
                val groups = seededWatershed(component, seeds, w)
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
            var best = Int.MAX_VALUE
            // Component-boundary distance transform: distance to the nearest
            // background cell, not distance to the image edge.
            for (dy in -8..8) for (dx in -8..8) {
                val nx = x + dx; val ny = y + dy
                if (nx !in 0 until w || ny !in 0 until mask.height || (ny * w + nx) !in inside) {
                    best = minOf(best, kotlin.math.abs(dx) + kotlin.math.abs(dy))
                }
            }
            best.toFloat()
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

    /** Deterministic seeded watershed approximation on the compact mask grid.
     * The highest-distance pixels are seeds; every foreground cell flows to
     * the nearest seed, which is equivalent to flooding this quantized mask
     * without introducing a heavyweight image-processing dependency. */
    private fun seededWatershed(component: List<Int>, seeds: List<Int>, width: Int): List<List<Int>> {
        val groups = MutableList(seeds.size) { mutableListOf<Int>() }
        component.forEach { pixel ->
            val owner = seeds.indices.minByOrNull { distance(pixel, seeds[it], width) } ?: return@forEach
            groups[owner] += pixel
        }
        return groups
    }
}

/** Coordinates explicit polygon re-analysis with the same track manager used by
 * the live detector. A stale result is rejected when the scope revision changed. */
class ScopedRefinementWorker(
    private val detector: ScopedObjectRefinement,
    private val scopeStore: SceneSearchScopeStore,
    private val tracks: ObjectTrackManager,
) {
    private var lastRevision: Long? = null
    private var fixedRevision: Long? = null
    private var fixedResult: List<TrackedSceneObject> = emptyList()
    private var analysisCount = 0

    fun refine(frame: AnalysisFrame): List<TrackedSceneObject> {
        val scope = scopeStore.current() as? DetectionSearchScope.Polygon ?: return emptyList()
        if (lastRevision != scope.revision) {
            tracks.reset()
            lastRevision = scope.revision
            fixedRevision = null
            fixedResult = emptyList()
            analysisCount = 0
        }
        if (fixedRevision == scope.revision) return fixedResult
        val result = detector.detectPolygon(frame, scope.region, scope.revision)
        if (!result.ran || result.scopeRevision != scope.revision) return emptyList()
        analysisCount++
        val candidates = result.objects.map { observation ->
            SceneObjectCandidate(
                box = observation.box,
                detectionConfidence = observation.detectionConfidence ?: observation.confidence,
                category = observation.category,
                classificationConfidence = observation.classificationConfidence,
                source = DetectionSource.SCOPE_CROP,
            )
        }
        val selected = tracks.update(result.scopeRevision, candidates)
            .filter { scope.region.accepts(it.box, minimumBoxOverlap = .50f) }
            .sortedByDescending { it.confidence }
        // A polygon search is a finite refinement session. Once every selected
        // object has three fresh hits, stop invoking the ROI detector; the guide
        // controller owns the subsequent fixed layout. This prevents the old
        // full-frame + ROI + segmentation loop from continuing behind a fixed
        // lasso result.
        // Do not freeze when the first object reaches three hits. The ROI
        // refinement observes three analyses so objects appearing in the
        // second or third result can join the same lasso selection.
        if (analysisCount >= 3) {
            fixedRevision = scope.revision
            // The lasso itself is the user's explicit evidence. Require two
            // observations when a track has history, but also keep a track
            // that is present in the third (final) ROI result. The old
            // hitCount >= 3 gate discarded objects that joined on result 2 or
            // 3, leaving only the first object in a multi-object lasso.
            fixedResult = selected
                .filter { it.hitCount >= 2 || it.missedFrames == 0 }
                .take(4)
        }
        return selected.take(4)
    }
}
