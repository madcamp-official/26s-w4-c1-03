package com.gamdo.app.detect

import com.gamdo.app.guide.ScenePolygonRegion

/** Applies scope semantics after fusion and before scene stabilization. */
class ScopeCandidateSelector {
    fun select(candidates: List<SceneObjectCandidate>, polygon: ScenePolygonRegion?): List<SceneObjectCandidate> {
        if (polygon == null) return candidates
        return candidates.filter { polygon.accepts(it.box, minimumBoxOverlap = .50f) }
    }
}
