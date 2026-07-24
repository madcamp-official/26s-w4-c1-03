package com.gamdo.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.gamdo.app.ui.permission.PermissionGate
import com.gamdo.app.ui.theme.GamdoTheme

/**
 * Root composable. Day 1: gates the app behind camera/photo permissions (§1-2);
 * once granted it shows the (still empty) main content. Navigation (§1-4)
 * replaces the placeholder body.
 */
@Composable
fun GamdoApp() {
    GamdoTheme {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            PermissionGate(modifier = Modifier.padding(innerPadding)) {
                MainPlaceholder()
            }
        }
    }
}

@Composable
private fun MainPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "감도 (GAMDO)")
    }
}

@Preview(showBackground = true)
@Composable
private fun GamdoAppPreview() {
    GamdoApp()
}
