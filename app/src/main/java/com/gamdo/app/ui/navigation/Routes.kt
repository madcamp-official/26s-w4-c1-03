package com.gamdo.app.ui.navigation

/**
 * Navigation routes. Adopting the simplified (t2) design: the camera IS the home
 * (no mood screen, no bottom bar), album is reached from the camera, and tapping
 * a photo opens the edit/result screen.
 */
object Routes {
    const val ONBOARDING = "onboarding"
    const val CAMERA = "camera" // home
    const val ALBUM = "album"

    const val RESULT = "result/{captureId}"
    fun result(captureId: String) = "result/$captureId"

    /**
     * The same 보정 screen, opened on a photo from the device library (W3.5-6).
     *
     * A separate route rather than a nullable argument on [RESULT], because the two
     * carry different things and one of them can be wrong: [RESULT] takes a
     * `captures` id and *looks it up*, so an id with no row leaves the screen loading
     * forever. This one takes the `MediaStore` id and rebuilds the content Uri
     * arithmetically — there is no lookup that can come back empty, so the route
     * cannot land in that state.
     *
     * The id, not a URL-encoded Uri: `ContentUris.withAppendedId(EXTERNAL_CONTENT_URI, id)`
     * is exactly how the album built the Uri it handed over, so the round trip is
     * lossless, and a `Long` path argument has no escaping to get wrong.
     */
    const val DEVICE_PHOTO = "device-photo/{mediaStoreId}"
    fun devicePhoto(mediaStoreId: Long) = "device-photo/$mediaStoreId"

    /**
     * The `나 찍어줘` hand-off (P2 §5): show a QR, watch for a friend's photos, collect
     * them.
     *
     * No arguments, and that is deliberate. The screen needs the camera's current
     * `GuideLayoutState` to build a `ShootPolicyV2`, which is an object graph, not a
     * string — so it is handed over in memory by whoever navigates here (see
     * `GamdoNavHost`'s `pendingShootLayout`) rather than encoded into the route.
     * Arriving with nothing — a deep link, or a process death that took the pending
     * value with it — is a supported state, not a crash: the screen shows 넘길 구도가
     * 없어요 and offers to close.
     *
     * A photo collected here becomes an ordinary `captures` row, so it is opened through
     * [result] — there is no separate route for a received photo, because after the import
     * there is nothing separate about it.
     *
     * The CAMP-2 web bundle is deployed. P1 owns the visible camera affordance and
     * invokes CameraScreen's `onOpenDelegatedShoot(layoutState.value)` callback; P2
     * owns this route, policy creation, polling and receive/claim.
     */
    const val SHOOT = "shoot"

    const val ARG_CAPTURE_ID = "captureId"
    const val ARG_MEDIA_STORE_ID = "mediaStoreId"
}
