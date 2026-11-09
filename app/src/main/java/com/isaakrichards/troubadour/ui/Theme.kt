package com.isaakrichards.troubadour.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Ink and gold by night, parchment and umber by day.
private val Dark = darkColorScheme(
    primary = Color(0xFFD4A84B),
    onPrimary = Color(0xFF2B1D04),
    primaryContainer = Color(0xFF4A3712),
    onPrimaryContainer = Color(0xFFF6DFA8),
    secondary = Color(0xFFC9B79C),
    background = Color(0xFF15110E),
    onBackground = Color(0xFFEDE3D4),
    surface = Color(0xFF15110E),
    onSurface = Color(0xFFEDE3D4),
    surfaceVariant = Color(0xFF2A241E),
    onSurfaceVariant = Color(0xFFCFC4B4),
    surfaceContainer = Color(0xFF211C17),
    surfaceContainerHigh = Color(0xFF2A241E),
    surfaceContainerHighest = Color(0xFF342D26),
    outline = Color(0xFF8F8576),
    error = Color(0xFFFFB4AB),
)

private val Light = lightColorScheme(
    primary = Color(0xFF7D5410),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDDA6),
    onPrimaryContainer = Color(0xFF281800),
    secondary = Color(0xFF6B5D49),
    background = Color(0xFFF7EFE2),
    onBackground = Color(0xFF201A13),
    surface = Color(0xFFF7EFE2),
    onSurface = Color(0xFF201A13),
    surfaceVariant = Color(0xFFEDE1CE),
    onSurfaceVariant = Color(0xFF4F4537),
    surfaceContainer = Color(0xFFEFE5D5),
    surfaceContainerHigh = Color(0xFFE9DECC),
    surfaceContainerHighest = Color(0xFFE3D7C3),
    outline = Color(0xFF817565),
    error = Color(0xFFBA1A1A),
)

@Composable
fun TroubadourTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        content = content,
    )
}
