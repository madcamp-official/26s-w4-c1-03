package com.gamdo.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// The app is dark-only by product decision (D10). No dynamic color, no light
// scheme — the photo is the subject and the chrome stays charcoal.
private val GamdoDarkColors = darkColorScheme(
    primary = Mint,
    onPrimary = Charcoal900,
    secondary = MintDim,
    onSecondary = Charcoal900,
    background = Charcoal900,
    onBackground = OnDarkHigh,
    surface = Charcoal800,
    onSurface = OnDarkHigh,
    surfaceVariant = Charcoal700,
    onSurfaceVariant = OnDarkMedium,
    outline = Charcoal600,
)

@Composable
fun GamdoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GamdoDarkColors,
        typography = GamdoTypography,
        content = content,
    )
}
