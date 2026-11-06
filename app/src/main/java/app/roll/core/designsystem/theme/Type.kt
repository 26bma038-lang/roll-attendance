package app.roll.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.roll.R

val PlexSans = FontFamily(
    Font(R.font.plex_sans_regular, FontWeight.Normal),
    Font(R.font.plex_sans_medium, FontWeight.Medium),
    Font(R.font.plex_sans_semibold, FontWeight.SemiBold),
)

/** Only for join codes and roll numbers. */
val PlexMono = FontFamily(
    Font(R.font.plex_mono_regular, FontWeight.Normal),
    Font(R.font.plex_mono_medium, FontWeight.Medium),
)

/**
 * Tabular figures on every style: times, counts and percentages are everywhere
 * in this app and should always line up in columns.
 */
private const val TabularFigures = "tnum"

private val Base = TextStyle(
    fontFamily = PlexSans,
    fontFeatureSettings = TabularFigures,
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None,
    ),
)

/**
 * The five-step scale from the brief, plus weight variants and the two mono styles.
 * Sizes are in sp so they follow the system font scale.
 */
@Immutable
data class RollTypography(
    /** 40/44. The overall percentage and nothing else. */
    val display: TextStyle = Base.copy(fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.Normal, letterSpacing = (-0.02).em),
    /** 20/26 medium. Screen titles and the date header. */
    val title: TextStyle = Base.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium),
    /** 16/22. Default reading text. */
    val body: TextStyle = Base.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
    /** 16/22 medium. The primary line of a row, button labels. */
    val bodyStrong: TextStyle = Base.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    /** 13/18 medium. Section labels, room/type, nav labels. */
    val label: TextStyle = Base.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    /** 12/16. Secondary lines and notes. */
    val caption: TextStyle = Base.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal),
    /** The join code, shown large. */
    val code: TextStyle = TextStyle(fontFamily = PlexMono, fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.12.em),
    /** Roll numbers in lists. */
    val rollNo: TextStyle = TextStyle(fontFamily = PlexMono, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
)

internal val LocalRollTypography = staticCompositionLocalOf { RollTypography() }

/** Maps the Roll scale onto Material slots so stock components (dialogs, snackbars, text fields) match. */
internal fun RollTypography.toMaterial(): Typography = Typography(
    displayLarge = display,
    displayMedium = display,
    displaySmall = display,
    headlineLarge = title,
    headlineMedium = title,
    headlineSmall = title,
    titleLarge = title,
    titleMedium = bodyStrong,
    titleSmall = label,
    bodyLarge = body,
    bodyMedium = body,
    bodySmall = caption,
    labelLarge = bodyStrong,
    labelMedium = label,
    labelSmall = caption,
)
