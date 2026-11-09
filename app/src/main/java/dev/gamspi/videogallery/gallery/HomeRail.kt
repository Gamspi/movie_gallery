package dev.gamspi.videogallery.gallery

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.gamspi.videogallery.ui.icons.AppIcons
import dev.gamspi.videogallery.ui.theme.AppColors

/** Collapsed rail width; also the start inset reserved for it by the screen. */
internal val RailCollapsedWidth = 56.dp
private val RailExpandedWidth = 176.dp
private val ItemShape = RoundedCornerShape(24.dp)

/** Where focus goes when leaving the rail to the right; set by the content currently shown. */
internal class RailLinks {
    var enter: () -> FocusRequester = { FocusRequester.Default }
}

private class RailItem(val tab: HomeTab, val icon: ImageVector, val label: String)

private val Items = listOf(
    RailItem(HomeTab.All, AppIcons.Grid, "Все"),
    RailItem(HomeTab.Movies, AppIcons.Movie, "Фильмы"),
    RailItem(HomeTab.Series, AppIcons.Tv, "Сериалы"),
    RailItem(HomeTab.Watchlist, AppIcons.Movie, "К просмотру"),
    RailItem(HomeTab.Watched, AppIcons.Grid, "Просмотренные"),
)

/**
 * Left rail with the home tabs: icons only, expands with labels while focus is inside.
 * [entry] is attached to the selected item, so Left from a card lands on the current tab.
 */
@Composable
internal fun HomeRail(
    selected: HomeTab,
    onSelect: (HomeTab) -> Unit,
    entry: FocusRequester,
    links: RailLinks,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val width by animateDpAsState(if (expanded) RailExpandedWidth else RailCollapsedWidth, label = "railWidth")
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(width)
            .background(if (expanded) AppColors.Surface else Color.Transparent)
            .padding(horizontal = 4.dp)
            .onFocusChanged { expanded = it.hasFocus },
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Items.forEachIndexed { index, item ->
            val isSelected = item.tab == selected
            Surface(
                onClick = { onSelect(item.tab) },
                modifier = Modifier
                    .then(if (isSelected) Modifier.focusRequester(entry) else Modifier)
                    .focusProperties {
                        // Keep focus inside the rail: the expanded rail overlaps content.
                        right = links.enter()
                        left = FocusRequester.Cancel
                        if (index == 0) up = FocusRequester.Cancel
                        if (index == Items.lastIndex) down = FocusRequester.Cancel
                    }
                    .height(48.dp)
                    .width(width - 8.dp),
                shape = ClickableSurfaceDefaults.shape(ItemShape),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (isSelected) AppColors.SurfaceVariant else Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    focusedContainerColor = AppColors.Accent,
                    focusedContentColor = AppColors.OnAccent,
                    pressedContainerColor = AppColors.Accent,
                    pressedContentColor = AppColors.OnAccent,
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            ) {
                Row(
                    modifier = Modifier.height(48.dp).padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(item.icon, contentDescription = item.label, modifier = Modifier.size(24.dp))
                    if (expanded) {
                        Text(
                            item.label,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
        }
    }
}
