package com.gamdo.app.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Explicit Photo Picker refinement path: at most twenty user-selected photos, two uploads at once. */
class ProfileRefinementRepository(
    private val references: ReferenceRepository,
    private val profiles: ProfileRepository,
) {
    suspend fun refineFromPhotos(context: Context, uris: List<Uri>): GamdoProfileV2 {
        require(uris.isNotEmpty()) { "at least one photo is required" }
        val bounded = uris.distinct().take(MAX_PHOTOS)
        val semaphore = Semaphore(MAX_CONCURRENT_ANALYSES)
        val resolutions = coroutineScope {
            bounded.map { uri -> async { semaphore.withPermit { references.resolve(context, uri) } } }.awaitAll()
        }
        val base = profiles.loadGamdoProfileV2() ?: error("onboarding profile is required")
        val refined = ProfileRefinementEngine.refine(base, resolutions, System.currentTimeMillis())
        profiles.saveGamdoProfileV2(refined)
        return refined
    }

    companion object {
        const val MAX_PHOTOS = 20
        const val MAX_CONCURRENT_ANALYSES = 2
    }
}
