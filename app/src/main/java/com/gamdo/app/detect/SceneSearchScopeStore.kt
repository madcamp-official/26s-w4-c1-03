package com.gamdo.app.detect

import com.gamdo.app.guide.PointN
import com.gamdo.app.guide.ScenePolygonRegion

sealed interface DetectionSearchScope {
    val revision: Long
    data class Default(override val revision: Long): DetectionSearchScope
    data class Tap(val point: PointN, override val revision: Long): DetectionSearchScope
    data class Polygon(val region: ScenePolygonRegion, override val revision: Long): DetectionSearchScope
}

/** Shared by the detector and guide controller so a new lasso invalidates all stale tracks. */
class SceneSearchScopeStore {
    private var revisionCounter = 0L
    private var currentScope: DetectionSearchScope = DetectionSearchScope.Default(0L)
    @Synchronized fun current(): DetectionSearchScope = currentScope
    @Synchronized fun setDefault(): DetectionSearchScope { currentScope = DetectionSearchScope.Default(++revisionCounter); return currentScope }
    @Synchronized fun setTap(point: PointN): DetectionSearchScope { currentScope = DetectionSearchScope.Tap(point, ++revisionCounter); return currentScope }
    @Synchronized fun setPolygon(region: ScenePolygonRegion): DetectionSearchScope { currentScope = DetectionSearchScope.Polygon(region, ++revisionCounter); return currentScope }
}
