package app.roll.core.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import app.roll.R
import app.roll.core.designsystem.icon.RollIcons
import app.roll.core.designsystem.preview.RollPreviewSurface
import app.roll.core.designsystem.preview.RollPreviews
import app.roll.core.designsystem.theme.Motion
import app.roll.core.designsystem.theme.RollTheme
import app.roll.core.designsystem.theme.Sizes
import app.roll.core.designsystem.theme.Spacing

/**
 * Left-aligned title on the background colour. No tint, no shadow.
 * Pass [onBack] to show a back arrow.
 */
@Composable
fun RollTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(RollTheme.colors.background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .heightIn(min = Sizes.topBarHeight)
            .padding(start = if (onBack != null) Spacing.xs else Spacing.gutter, end = Spacing.xs),
    ) {
        if (onBack != null) {
            RollIconButton(
                icon = RollIcons.Back,
                contentDescription = stringResource(R.string.action_back),
                onClick = onBack,
            )
            Spacer(Modifier.width(Spacing.xs))
        }
        Text(
            text = title,
            style = RollTheme.typography.title,
            color = RollTheme.colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        actions()
    }
}

@Immutable
data class RollNavItem(
    val label: String,
    @param:DrawableRes val icon: Int,
)

/**
 * Plain icons and labels. The selected item is ink-coloured with a 2dp line above it;
 * no tinted pill.
 */
@Composable
fun RollBottomBar(
    items: List<RollNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(RollTheme.colors.background),
    ) {
        HairlineDivider()
        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .heightIn(min = Sizes.bottomBarHeight)
                .selectableGroup(),
        ) {
            items.forEachIndexed { index, item ->
                NavItem(
                    item = item,
                    selected = index == selectedIndex,
                    onClick = { onSelect(index) },
                )
            }
        }
    }
}

@Composable
private fun RowScope.NavItem(
    item: RollNavItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = RollTheme.colors
    val tint by animateColorAsState(
        targetValue = if (selected) colors.ink else colors.inkSecondary,
        animationSpec = Motion.short(),
        label = "nav tint",
    )
    val indicator by animateColorAsState(
        targetValue = if (selected) colors.ink else Color.Transparent,
        animationSpec = Motion.short(),
        label = "nav indicator",
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .weight(1f)
            .heightIn(min = Sizes.bottomBarHeight)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
    ) {
        Box(
            Modifier
                .size(width = Sizes.navIndicatorWidth, height = Sizes.navIndicatorHeight)
                .background(indicator),
        )
        Spacer(Modifier.height(Spacing.sm))
        Icon(
            painter = painterResource(item.icon),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(Sizes.icon),
        )
        // Six tabs (CRs) on a narrow phone: long labels such as "Department" step down in size
        // instead of being clipped.
        val style = navLabelStyle()
        BasicText(
            text = item.label,
            style = style.copy(color = tint),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = style.fontSize, stepSize = 0.5.sp),
            modifier = Modifier.padding(top = Spacing.xxs, bottom = Spacing.sm, start = Spacing.xxs, end = Spacing.xxs),
        )
    }
}

/** A quiet label above a group of rows. Sentence case, secondary ink. */
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = Spacing.gutter, end = Spacing.xs, top = Spacing.xl, bottom = Spacing.sm),
    ) {
        Text(
            text = text,
            style = RollTheme.typography.label,
            color = RollTheme.colors.inkSecondary,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        trailing()
    }
}

@RollPreviews
@Composable
private fun BarsPreview() {
    RollPreviewSurface {
        Column {
            RollTopAppBar(title = "Thursday, 5 Nov") {
                RollIconButton(icon = RollIcons.Settings, contentDescription = "Settings", onClick = {})
            }
            RollTopAppBar(title = "Design and Analysis of Algorithms", onBack = {})
            SectionHeader(text = "Today's classes")
            RollBottomBar(
                items = listOf(
                    RollNavItem("Today", RollIcons.Today),
                    RollNavItem("Subjects", RollIcons.Subjects),
                    RollNavItem("History", RollIcons.History),
                    RollNavItem("Manage", RollIcons.Manage),
                ),
                selectedIndex = 0,
                onSelect = {},
            )
        }
    }
}

/**
 * Nav labels stop growing at 130% font scale: four labels must share one row, and the
 * screens themselves still scale fully.
 */
@Composable
private fun navLabelStyle(): TextStyle {
    val style = RollTheme.typography.label
    val fontScale = LocalDensity.current.fontScale
    val cap = 1.3f
    return if (fontScale <= cap) style else style.copy(
        fontSize = style.fontSize * (cap / fontScale),
        lineHeight = style.lineHeight * (cap / fontScale),
    )
}
