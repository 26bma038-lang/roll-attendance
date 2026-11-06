package app.roll.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Every colour in the app. Composables read these through [RollTheme.colors];
 * nothing outside this file should construct a [Color] from a hex value.
 */
@Immutable
data class RollColors(
    val background: Color,
    val surface: Color,
    val ink: Color,
    val inkSecondary: Color,
    val hairline: Color,
    /** Borders on interactive controls (toggle, outlined button). Hairline is too faint for a tap target. */
    val outline: Color,
    val accent: Color,
    val onAccent: Color,
    val warning: Color,
    val onWarning: Color,
    val cancelled: Color,
    /** Muted tones used only as the 3dp bar on the left of a row. Indexed by `colorIndex`. */
    val subjects: List<Color>,
    val isDark: Boolean,
) {
    fun subject(index: Int): Color = subjects[index.mod(subjects.size)]
}

val LightRollColors = RollColors(
    background = Color(0xFFF6F5F1),
    surface = Color(0xFFFFFFFF),
    ink = Color(0xFF1A1A18),
    inkSecondary = Color(0xFF6B6A65),
    hairline = Color(0xFFE3E1DA),
    outline = Color(0xFFC9C6BC),
    accent = Color(0xFF1E5E45),
    onAccent = Color(0xFFFFFFFF),
    warning = Color(0xFFA8402B),
    onWarning = Color(0xFFFFFFFF),
    cancelled = Color(0xFF9C9A93),
    subjects = listOf(
        Color(0xFF4F6E96), // denim
        Color(0xFF3F7F7A), // teal
        Color(0xFF6F7F45), // moss
        Color(0xFFB0873A), // ochre
        Color(0xFF9C6446), // sienna
        Color(0xFF9A5A68), // rosewood
        Color(0xFF7E796D), // stone
        Color(0xFF5E6A78), // slate
    ),
    isDark = false,
)

val DarkRollColors = RollColors(
    background = Color(0xFF121211),
    surface = Color(0xFF1B1B19),
    ink = Color(0xFFECEAE4),
    inkSecondary = Color(0xFF9A988F),
    hairline = Color(0xFF2C2B28),
    outline = Color(0xFF46443F),
    accent = Color(0xFF5FB38C),
    // The dark accent and warning are light tones; white letters on them fall
    // below 3:1, so selected segments use the dark background colour instead.
    onAccent = Color(0xFF121211),
    warning = Color(0xFFE07A62),
    onWarning = Color(0xFF121211),
    cancelled = Color(0xFF6F6D66),
    subjects = listOf(
        Color(0xFF8DA6C8),
        Color(0xFF7FB5AF),
        Color(0xFFA6B57E),
        Color(0xFFD6B26E),
        Color(0xFFCC9474),
        Color(0xFFC78E9A),
        Color(0xFFABA698),
        Color(0xFF96A3B2),
    ),
    isDark = true,
)

internal val LocalRollColors = staticCompositionLocalOf { LightRollColors }
