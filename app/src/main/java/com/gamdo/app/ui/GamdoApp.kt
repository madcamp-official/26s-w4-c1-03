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
import com.gamdo.app.ui.theme.GamdoTheme

/**
 * Root composable. Day 1 §1-1 completion criterion is only "an empty Compose
 * screen builds and runs on a device". Navigation (§1-4) replaces this body.
 */
@Composable
fun GamdoApp() {
    GamdoTheme {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "감도 (GAMDO)")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun GamdoAppPreview() {
    GamdoApp()
}
