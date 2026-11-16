package com.starpick.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val StarColors = darkColorScheme(
    primary = Accent.Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF16305A),
    onPrimaryContainer = Color(0xFFD7E7FB),
    secondary = Accent.Gold,
    onSecondary = Color(0xFF1A1200),
    secondaryContainer = Color(0xFF3A2A0A),
    onSecondaryContainer = Accent.Gold,
    tertiary = Accent.Online,
    onTertiary = Color(0xFF03281C),
    background = Ink.Base,
    onBackground = Ink.Text1,
    surface = Ink.Card,
    onSurface = Ink.Text1,
    surfaceVariant = Ink.Selected,
    onSurfaceVariant = Ink.Text2,
    surfaceTint = Accent.Brand,
    inverseSurface = Ink.Text1,
    inverseOnSurface = Ink.Base,
    error = Accent.New,
    onError = Color.White,
    outline = Ink.Text3,
    outlineVariant = Ink.Line,
    scrim = Color(0xCC000000),
)

@Composable
fun StarpickTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = StarColors,
        typography = StarTypography,
        content = content,
    )
}
