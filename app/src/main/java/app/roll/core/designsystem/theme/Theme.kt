package app.roll.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

/**
 * App theme. Dynamic colour is intentionally not supported: the palette is fixed.
 */
@Composable
fun RollTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkRollColors else LightRollColors
    val typography = RollTypography()
    CompositionLocalProvider(
        LocalRollColors provides colors,
        LocalRollTypography provides typography,
    ) {
        MaterialTheme(
            colorScheme = colors.toMaterial(),
            typography = typography.toMaterial(),
            shapes = MaterialShapes,
            content = content,
        )
    }
}

object RollTheme {
    val colors: RollColors
        @Composable @ReadOnlyComposable get() = LocalRollColors.current

    val typography: RollTypography
        @Composable @ReadOnlyComposable get() = LocalRollTypography.current
}

/**
 * Material's slots, filled from Roll tokens so stock components never fall back to
 * the baseline purple. Surface tints are disabled by mapping every container to a flat colour.
 */
private fun RollColors.toMaterial(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = surface,
        onPrimaryContainer = ink,
        inversePrimary = if (isDark) LightRollColors.accent else DarkRollColors.accent,
        secondary = inkSecondary,
        onSecondary = background,
        secondaryContainer = hairline,
        onSecondaryContainer = ink,
        tertiary = accent,
        onTertiary = onAccent,
        tertiaryContainer = surface,
        onTertiaryContainer = ink,
        background = background,
        onBackground = ink,
        surface = background,
        onSurface = ink,
        surfaceVariant = surface,
        onSurfaceVariant = inkSecondary,
        surfaceTint = background,
        inverseSurface = ink,
        inverseOnSurface = background,
        error = warning,
        onError = onWarning,
        errorContainer = surface,
        onErrorContainer = warning,
        outline = outline,
        outlineVariant = hairline,
        scrim = ink.copy(alpha = 0.32f),
        surfaceBright = surface,
        surfaceDim = background,
        surfaceContainerLowest = surface,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = surface,
        surfaceContainerHighest = surface,
    )
}
