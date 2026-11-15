package com.starpick.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val StarBoxColors = lightColorScheme(
    primary = Color(0xFF1D4E89),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD7E7FB),
    onPrimaryContainer = Color(0xFF0B2E57),
    secondary = Color(0xFFB87514),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFFBF8F3),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFFFFBFE),
    onSurface = Color(0xFF191C20),
)

@Composable
fun StarpickTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = StarBoxColors,
        typography = Typography(),
        content = content,
    )
}
