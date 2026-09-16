package com.elham.priorityringer.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** [StatusPalette] for the current theme. Read via [PriorityRingerTheme.status]. */
val LocalStatusPalette = staticCompositionLocalOf { LightStatusPalette }

private val LightScheme = lightColorScheme(
    primary = BrandBlue40,
    onPrimary = Color.White,
    primaryContainer = BrandBlueContainerLight,
    onPrimaryContainer = OnBrandBlueContainerLight,
    secondary = AccentTeal40,
    onSecondary = Color.White,
    secondaryContainer = AccentTealContainerLight,
    onSecondaryContainer = OnAccentTealContainerLight,
    background = SurfaceLight,
    onBackground = OnSurfaceLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceContainerLight,
    onSurfaceVariant = OnSurfaceLight,
    outline = OutlineLight,
    error = ErrorLight,
    onError = Color.White,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
)

private val DarkScheme = darkColorScheme(
    primary = BrandBlue80,
    onPrimary = OnBrandBlueContainerLight,
    primaryContainer = BrandBlueContainerDark,
    onPrimaryContainer = BrandBlueContainerLight,
    secondary = AccentTeal80,
    onSecondary = OnAccentTealContainerLight,
    secondaryContainer = AccentTealContainerDark,
    onSecondaryContainer = AccentTealContainerLight,
    background = SurfaceDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceContainerDark,
    onSurfaceVariant = OnSurfaceDark,
    outline = OutlineDark,
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
)

/**
 * Larger and heavier than the M3 defaults throughout — this app is read by
 * family members setting up a phone for someone else, often in a hurry, and
 * § 12 makes accessibility an explicit goal rather than a nicety.
 */
private val AppTypography = Typography(
    headlineSmall = TextStyle(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 23.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun PriorityRingerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalStatusPalette provides if (darkTheme) DarkStatusPalette else LightStatusPalette,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = AppTypography,
            content = content,
        )
    }
}

/** Convenience accessor mirroring [MaterialTheme]. */
object PriorityRingerTheme {
    val status: StatusPalette
        @Composable get() = LocalStatusPalette.current
}
