package com.openjarvis.ui.theme

import androidx.compose.ui.graphics.Color

object VoidColor {
    val Amber = Color(0xFFFFC857)
    val BorderGlow = Color(0xFF6547A8)
    val BorderSubtle = Color(0xFF29243A)
    val Cyan = Color(0xFF55D6E8)
    val Green = Color(0xFF4CAF82)
    val Red = Color(0xFFFF5D73)
    val TextDisabled = Color(0xFF686477)
    val TextPrimary = Color(0xFFF4F0FF)
    val TextSecondary = Color(0xFFB4ADC9)
    val Violet = Color(0xFF9B7BFF)
    val VioletDim = Color(0xFF5A438F)
    val Void600 = Color(0xFF403952)
    val Void700 = Color(0xFF302940)
    val Void800 = Color(0xFF241F32)
    val Void900 = Color(0xFF191523)
    val Void950 = Color(0xFF100D17)
}

private val DarkColorScheme = androidx.compose.material3.darkColorScheme(
    primary = VoidColor.Violet,
    onPrimary = VoidColor.TextPrimary,
    secondary = VoidColor.Cyan,
    onSecondary = VoidColor.Void950,
    background = VoidColor.Void950,
    onBackground = VoidColor.TextPrimary,
    surface = VoidColor.Void900,
    onSurface = VoidColor.TextPrimary,
    surfaceVariant = VoidColor.Void800,
    onSurfaceVariant = VoidColor.TextSecondary,
    error = VoidColor.Red,
    onError = VoidColor.Void950
)

@androidx.compose.runtime.Composable
fun OpenJarvisTheme(content: @androidx.compose.runtime.Composable () -> Unit) {
    androidx.compose.material3.MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = androidx.compose.material3.Typography(),
        content = content
    )
}
