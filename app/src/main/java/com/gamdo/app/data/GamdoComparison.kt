package com.gamdo.app.data

import com.gamdo.app.data.preset.ColorParams

/** Demo-only contrast profile. It is never persisted as a user's preference. */
object DemoContrastProfile {
    val profile = GamdoProfileV2(
        global = GamdoPolicy(
            capture = CapturePreference(
                preferredZoom = 1f,
                subjectScale = 0.62f,
                anchorX = 0.5f,
                anchorY = 0.54f,
                flash = FlashPreference.ON,
                backgroundRatio = 0.26f,
                poseMood = PoseMood.CANDID,
            ),
            color = ColorParams(4600.0, 0.10, 0.42, 0.10, 0.08, 0.12, 0.0, 0.06),
            evidence = listOf(PreferenceEvidence(EvidenceSource.CARD, 0, "demo_contrast_profile")),
            confidence = 1f,
        ),
        updatedAt = 0L,
    )
}

data class GamdoComparison(
    val user: GamdoPolicy,
    val demo: GamdoPolicy,
    val context: SceneContext,
    val demoOnly: Boolean = true,
)

object GamdoComparisonEngine {
    fun compare(current: GamdoProfileV2, context: SceneContext): GamdoComparison = GamdoComparison(
        user = current.policyFor(context),
        demo = DemoContrastProfile.profile.policyFor(context),
        context = context,
    )
}
