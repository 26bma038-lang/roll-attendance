package app.roll.core.designsystem.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** 4dp grid. */
object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp

    /** Left and right screen gutter. */
    val gutter = 16.dp
}

object Sizes {
    val rowMinHeight = 64.dp
    val rowTallHeight = 72.dp
    val subjectBarWidth = 3.dp
    val minTouchTarget = 44.dp
    val toggleSegmentWidth = 44.dp
    val toggleSegmentHeight = 36.dp
    val buttonHeight = 48.dp
    val topBarHeight = 56.dp
    val bottomBarHeight = 64.dp
    val navIndicatorHeight = 2.dp
    val navIndicatorWidth = 24.dp
    val icon = 24.dp
    /** Width of the time column in session rows; grows with font scale. */
    val timeColumnMinWidth = 52.dp
}

/** 10dp for sheets and buttons, 6dp for the toggle. Nothing rounder. */
object RollShapes {
    val control = RoundedCornerShape(10.dp)
    val sheet = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)
    val toggle = RoundedCornerShape(6.dp)
    val small = RoundedCornerShape(6.dp)
}

internal val MaterialShapes = Shapes(
    extraSmall = RollShapes.small,
    small = RollShapes.small,
    medium = RollShapes.control,
    large = RollShapes.control,
    extraLarge = RollShapes.control,
)

/** 150–200ms, standard easing. No springs. */
object Motion {
    const val SHORT = 150
    const val MEDIUM = 200
    val StandardEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    fun <T> short(): FiniteAnimationSpec<T> = tween(SHORT, easing = StandardEasing)
    fun <T> medium(): FiniteAnimationSpec<T> = tween(MEDIUM, easing = StandardEasing)
}
