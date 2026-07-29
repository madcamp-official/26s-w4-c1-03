package com.gamdo.app

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.gamdo.app.camera.SceneDetectorWarmup
import com.gamdo.app.data.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application entry point. Holds the [AppContainer] and, on first launch, ensures
 * the device UUID exists and the bundled presets are seeded (§1-3).
 */
class GamdoApplication : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        appScope.launch {
            container.deviceIdStore.getOrCreate()
            container.presetRepository.seedFromAssetsIfEmpty()
        }
        warmSceneDetector()
    }

    /**
     * Starts the 7.6s object-detection model load at the earliest point the policy
     * allows (see [com.gamdo.app.camera.DetectorWarmupGate]).
     *
     * A **separate** `launch` from the seeding above, not another statement inside
     * it: `seedFromAssetsIfEmpty()` reads an asset and writes rows, and queueing
     * the model load behind it would give back the head start this is here to buy.
     *
     * Nothing here runs on the main thread and nothing delays `onCreate` — the
     * whole body is one coroutine dispatch. What it costs a *returning* user is one
     * background thread and ~5MB from this point instead of from the camera screen;
     * what it costs a **first-run** user is the flag read and nothing else, because
     * the policy refuses to preload for someone who is on their way to onboarding.
     *
     * Also the recovery path after a memory trim released the stack: `MainActivity`
     * calls this from `onStart`, and the [SceneDetectorWarmup.needsWarmUp] check
     * keeps every ordinary resume from paying for a Room read it cannot act on.
     */
    fun warmSceneDetector() {
        if (!SceneDetectorWarmup.needsWarmUp()) return
        appScope.launch {
            SceneDetectorWarmup.preload(
                context = this@GamdoApplication,
                // Room is built with no destructive-migration fallback on purpose,
                // so this read *can* throw. That crash belongs to the navigation
                // path that actually needs the answer, where it is legible — not to
                // a speculative warm-up on a background thread, where an unhandled
                // coroutine exception would take the process down with a stack that
                // points at the wrong thing. Failing closed costs a cold detector.
                onboardingComplete = runCatching { container.settingsRepository.isOnboardingDone() }
                    .getOrDefault(false),
                cameraPermissionGranted = hasCameraPermission(),
            )
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        SceneDetectorWarmup.onTrimMemory(level)
    }

    /**
     * Checked rather than assumed: a user who refused the camera never reaches a
     * preview, and the model would be ~5MB held for a screen they cannot open.
     */
    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
}
