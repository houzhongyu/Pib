package com.houxinzhe.pib.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** iOS system colours, so the Android build matches the iOS PIB screens in light and dark. */
@Immutable
data class Palette(
    val background: Color,
    val secondaryBackground: Color,
    val groupedBackground: Color,
    val groupedCell: Color,
    val moreBackground: Color,
    val label: Color,
    val secondaryLabel: Color,
    val tertiaryLabel: Color,
    val separator: Color,
    val quaternary: Color,
    val blue: Color,
    val purple: Color,
    val red: Color,
    val green: Color,
    val dark: Boolean,
)

val LightPalette = Palette(
    background = Color(0xFFFFFFFF),
    secondaryBackground = Color(0xFFF2F2F7),
    groupedBackground = Color(0xFFF2F2F7),
    groupedCell = Color(0xFFFFFFFF),
    moreBackground = Color(0xFFFBFBFB),
    label = Color(0xFF000000),
    secondaryLabel = Color(0x993C3C43),
    tertiaryLabel = Color(0x4D3C3C43),
    separator = Color(0x4A3C3C43),
    quaternary = Color(0x1F3C3C43),
    blue = Color(0xFF007AFF),
    purple = Color(0xFFAF52DE),
    red = Color(0xFFFF3B30),
    green = Color(0xFF34C759),
    dark = false,
)

val DarkPalette = Palette(
    background = Color(0xFF000000),
    secondaryBackground = Color(0xFF1C1C1E),
    groupedBackground = Color(0xFF000000),
    groupedCell = Color(0xFF1C1C1E),
    moreBackground = Color(0xFF000000),
    label = Color(0xFFFFFFFF),
    secondaryLabel = Color(0x99EBEBF5),
    tertiaryLabel = Color(0x4DEBEBF5),
    separator = Color(0x99545458),
    quaternary = Color(0x2EEBEBF5),
    blue = Color(0xFF0A84FF),
    purple = Color(0xFFBF5AF2),
    red = Color(0xFFFF453A),
    green = Color(0xFF30D158),
    dark = true,
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

@Composable
fun PibTheme(content: @Composable () -> Unit) {
    val p = if (isSystemInDarkTheme()) DarkPalette else LightPalette
    val scheme = if (p.dark) darkColorScheme(
        primary = p.blue, onPrimary = Color.White, background = p.background, surface = p.secondaryBackground,
        onBackground = p.label, onSurface = p.label, surfaceVariant = p.secondaryBackground, onSurfaceVariant = p.secondaryLabel,
        outline = p.separator, outlineVariant = p.separator, error = p.red, secondaryContainer = p.secondaryBackground,
        surfaceContainerHigh = Color(0xFF2C2C2E), surfaceContainer = Color(0xFF1C1C1E),
    ) else lightColorScheme(
        primary = p.blue, onPrimary = Color.White, background = p.background, surface = p.background,
        onBackground = p.label, onSurface = p.label, surfaceVariant = p.secondaryBackground, onSurfaceVariant = p.secondaryLabel,
        outline = p.separator, outlineVariant = p.separator, error = p.red, secondaryContainer = p.secondaryBackground,
        surfaceContainerHigh = Color(0xFFFFFFFF), surfaceContainer = Color(0xFFF2F2F7),
    )
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
