package com.gamdo.app.data

import com.gamdo.app.guide.StyleTarget

/**
 * UI-independent hand-off from a live scene to the contextual GAMDO policy.
 *
 * The camera screen owns CameraX and the presentation of this result. Keeping
 * the scene classification here prevents the camera, rescue, and QR flows from
 * silently applying three different definitions of "night portrait".
 */
data class CameraSceneSignals(
    val personCount: Int,
    val objectLabels: Set<String> = emptySet(),
    val brightness: Float,
    val subjectScale: Float,
)

data class ResolvedCameraPolicy(
    val context: SceneContext,
    val policy: GamdoPolicy,
    val guideTarget: StyleTarget,
) {
    val preferredZoom: Float get() = policy.capture.preferredZoom
    val flashPreference: FlashPreference get() = policy.capture.flash
}

object CameraProfilePolicyResolver {
    /** Falls back to the global policy when contextual evidence is unavailable. */
    fun resolve(profile: GamdoProfileV2, signals: CameraSceneSignals): ResolvedCameraPolicy {
        val context = SceneContextResolver.resolve(
            personCount = signals.personCount,
            objectLabels = signals.objectLabels,
            brightness = signals.brightness,
            subjectScale = signals.subjectScale,
        )
        val policy = profile.policyFor(context)
        return ResolvedCameraPolicy(
            context = context,
            policy = policy,
            guideTarget = policy.toCameraStyleTarget(),
        )
    }
}
