package com.gamdo.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// Dark-only by product decision (D10): charcoal chrome, sage single accent, no
// dynamic color, no light scheme — the photo is the subject.
private val GamdoDarkColors = darkColorScheme(
    primary = SageButton,
    onPrimary = OnSage,
    secondary = Sage,
    onSecondary = OnSage,
    tertiary = SageDim,
    onTertiary = OnSage,
    background = Charcoal900,
    onBackground = OnDarkHigh,
    surface = Charcoal700,
    onSurface = OnDarkHigh,
    surfaceVariant = Charcoal600,
    onSurfaceVariant = OnDarkMedium,
    outline = OutlineDim,
)

@Composable
fun GamdoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GamdoDarkColors,
        typography = GamdoTypography,
        content = content,
    )
}
