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

    const val ARG_CAPTURE_ID = "captureId"
    const val ARG_MEDIA_STORE_ID = "mediaStoreId"
}
