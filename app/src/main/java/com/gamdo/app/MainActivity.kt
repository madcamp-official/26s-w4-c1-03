package com.gamdo.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.gamdo.app.ui.GamdoApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Both bars pinned to the app's own theme, not the system's.
        //
        // The no-argument `enableEdgeToEdge()` uses SystemBarStyle.auto, whose
        // detectDarkMode reads the *device* configuration. This app is dark-only
        // (D10/D11) with a charcoal background, so on a phone set to light mode —
        // the common case — the status-bar clock, battery and signal icons were
        // drawn in black on #0C0D0B and effectively disappeared on every screen.
        // There is no values-night or setDefaultNightMode to correct it elsewhere.
        //
        // `dark(...)` forces light-on-transparent icons regardless of the device
        // setting, which is the only correct answer for a UI that is always dark.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            GamdoApp()
        }
    }

    /**
     * Re-arms the detector warm-up on return to the foreground.
     *
     * `Application.onTrimMemory` gives the model back when the app stops being
     * visible with no camera screen mounted, and becoming visible again is the
     * first moment it is worth paying for once more — earlier than any composable,
     * and early enough to overlap the activity restart. A no-op when the stack is
     * already warm, which is every launch that was not preceded by a trim.
     */
    override fun onStart() {
        super.onStart()
        (application as? GamdoApplication)?.warmSceneDetector()
    }
}
