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

    const val ARG_CAPTURE_ID = "captureId"
}
