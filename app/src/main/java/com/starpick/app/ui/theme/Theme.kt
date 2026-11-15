package com.starpick.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 品牌蓝为主色，辅以暖金点缀（呼应「星匣」的星空与收藏感）。
private val StarBoxColors = lightColorScheme(
    primary = Color(0xFF1D4E89),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD7E7FB),
    onPrimaryContainer = Color(0xFF0B2E57),
    secondary = Color(0xFFB87514),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFBE2BC),
    onSecondaryContainer = Color(0xFF523000),
    tertiary = Color(0xFF3E6B54),
    background = Color(0xFFF7F5F0),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE7E4DE),
    onSurfaceVariant = Color(0xFF5E6066),
    outline = Color(0xFF8B8D93),
)

@Composable
fun StarpickTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = StarBoxColors,
        typography = Typography(),
        content = content,
    )
}
