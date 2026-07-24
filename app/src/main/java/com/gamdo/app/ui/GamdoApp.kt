package com.gamdo.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.gamdo.app.GamdoApplication
import com.gamdo.app.ui.navigation.GamdoNavHost
import com.gamdo.app.ui.permission.PermissionGate
import com.gamdo.app.ui.theme.GamdoTheme

/**
 * Root composable. Gates the app behind camera/photo permissions (§1-2), then
 * hands off to the navigation graph (§1-4): onboarding → camera(home) → album → result.
 */
@Composable
fun GamdoApp() {
    GamdoTheme {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            val context = LocalContext.current
            val container = remember(context) {
                (context.applicationContext as GamdoApplication).container
            }
            PermissionGate(modifier = Modifier.padding(innerPadding)) {
                GamdoNavHost(container = container, modifier = Modifier.fillMaxSize())
            }
        }
    }
}
