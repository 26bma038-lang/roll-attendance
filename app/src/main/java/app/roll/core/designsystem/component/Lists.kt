package app.roll.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.roll.core.designsystem.icon.RollIcons
import app.roll.core.designsystem.preview.RollPreviewSurface
import app.roll.core.designsystem.preview.RollPreviews
import app.roll.core.designsystem.theme.Motion
import app.roll.core.designsystem.theme.RollTheme
import app.roll.core.designsystem.theme.Sizes
import app.roll.core.designsystem.theme.Spacing

/** A generic settings / navigation row: title, optional subtitle, trailing slot or chevron. */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    titleColor: Color = RollTheme.colors.ink,
    showChevron: Boolean = onClick != null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) { }
            .padding(start = Spacing.gutter, end = if (trailing != null || showChevron) Spacing.sm else Spacing.gutter),
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(Spacing.md))
        }
        Column(
            Modifier
                .weight(1f)
                .padding(vertical = Spacing.md),
        ) {
            Text(text = title, style = RollTheme.typography.body, color = titleColor)
            if (subtitle != null) {
                Text(text = subtitle, style = RollTheme.typography.caption, color = RollTheme.colors.inkSecondary)
            }
        }
        trailing?.invoke(this)
        if (showChevron && trailing == null) {
            Icon(
                painter = painterResource(RollIcons.ChevronRight),
                contentDescription = null,
                tint = RollTheme.colors.inkSecondary,
                modifier = Modifier
                    .padding(horizontal = Spacing.sm)
                    .size(Sizes.icon),
            )
        }
    }
}

/** Rows on the surface colour with hairlines between them. The standard list block. */
@Composable
fun ListGroup(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(RollTheme.colors.surface),
    ) {
        content()
    }
}

/** Text tabs with a 2dp ink underline on the selected one. Equal widths. */
@Composable
fun RollTabRow(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RollTheme.colors
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .selectableGroup(),
        ) {
            tabs.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                val tint by animateColorAsState(
                    if (selected) colors.ink else colors.inkSecondary,
                    animationSpec = Motion.short(),
                    label = "tab tint",
                )
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = Sizes.buttonHeight)
                        .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(index) }),
                ) {
                    Text(
                        text = label,
                        style = RollTheme.typography.label,
                        color = tint,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        modifier = Modifier.padding(horizontal = Spacing.xs, vertical = Spacing.md),
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.sm)
                            .height(Sizes.navIndicatorHeight)
                            .background(if (selected) colors.ink else Color.Transparent),
                    )
                }
            }
        }
        HairlineDivider()
    }
}

/** "Will sync when online": shown under a header while writes are queued. */
@Composable
fun OfflineLine(
    modifier: Modifier = Modifier,
    text: String = "Will sync when online",
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.xs),
    ) {
        Icon(
            painter = painterResource(RollIcons.Offline),
            contentDescription = null,
            tint = RollTheme.colors.inkSecondary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(text = text, style = RollTheme.typography.caption, color = RollTheme.colors.inkSecondary)
    }
}

@RollPreviews
@Composable
private fun ListsPreview() {
    RollPreviewSurface {
        Column {
            RollTabRow(tabs = listOf("Present 38", "Absent 4", "Not marked 12"), selectedIndex = 0, onSelect = {})
            OfflineLine()
            ListGroup {
                ListRow(title = "Timetable", subtitle = "26 slots, Mon–Sat", onClick = {})
                HairlineDivider(startIndent = Spacing.gutter)
                ListRow(title = "Class settings", onClick = {})
            }
        }
    }
}
