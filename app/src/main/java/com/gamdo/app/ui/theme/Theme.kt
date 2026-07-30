package com.gamdo.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// Dark-only by product decision (D10, unchanged by the 2026-07-30 redesign): ink
// chrome, a single amber accent, no dynamic colour, no light scheme — the photo is
// the subject.
//
// primary/secondary/tertiary are all [Amber] on purpose. Material wants three
// accents; the design allows one, so the extra slots point at it rather than
// inventing two more colours the spec does not have. Any component that leans on
// them to distinguish itself is asking for a distinction this app does not make.
private val GamdoDarkColors = darkColorScheme(
    primary = Amber,
    onPrimary = OnAmber,
    secondary = Amber,
    onSecondary = OnAmber,
    tertiary = Amber,
    onTertiary = OnAmber,
    background = Ink900,
    onBackground = TextHi,
    surface = Ink800,
    onSurface = TextHi,
    surfaceVariant = Ink700,
    onSurfaceVariant = TextMid,
    outline = Outline,
)

@Composable
fun GamdoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GamdoDarkColors,
        typography = GamdoTypography,
        content = content,
    )
}
