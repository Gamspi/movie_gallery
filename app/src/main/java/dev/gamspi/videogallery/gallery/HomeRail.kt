package dev.gamspi.videogallery.gallery

import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.gamspi.videogallery.ui.icons.AppIcons
import dev.gamspi.videogallery.ui.theme.AppColors

/** Collapsed rail width; also the start inset reserved for it by the screen. */
internal val RailCollapsedWidth = 56.dp
private val RailExpandedWidth = 208.dp
// Scrim fade beyond the rail edge, collapsed and expanded.
/** Gap between the collapsed rail and the content; the collapsed scrim fades out across it. */
internal val RailGap = 20.dp
private val ScrimFadeCollapsed = RailGap
private val ScrimFadeExpanded = 96.dp
private const val ScrimAlpha = 0.92f
private const val ExpandMillis = 220
private val ItemShape = RoundedCornerShape(24.dp)

/** Where focus goes when leaving the rail to the right; set by the content currently shown. */
internal class RailLinks {
    var enter: () -> FocusRequester = { FocusRequester.Default }

    /** Root y (px) where the scrim starts, so the gallery header above it stays unshaded; 0 = top. */
    var scrimTop by mutableFloatStateOf(0f)
}

private class RailItem(val tab: HomeTab, val icon: ImageVector, val label: String)

private val Items = listOf(
    RailItem(HomeTab.All, AppIcons.Grid, "Все"),
    RailItem(HomeTab.Movies, AppIcons.Movie, "Фильмы"),
    RailItem(HomeTab.Series, AppIcons.Tv, "Сериалы"),
    RailItem(HomeTab.Watchlist, AppIcons.Movie, "К просмотру"),
    RailItem(HomeTab.Watched, AppIcons.Grid, "Просмотренные"),
    RailItem(HomeTab.ForYou, AppIcons.Star, "Для вас"),
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
    collapsedScrim: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    // One progress drives width, scrim and labels, so expand and collapse stay in step.
    val progress by animateFloatAsState(
        if (expanded) 1f else 0f,
        tween(ExpandMillis, easing = FastOutSlowInEasing),
        label = "railExpand",
    )
    val width = lerp(RailCollapsedWidth, RailExpandedWidth, progress)
    val scrimWidth = width + lerp(ScrimFadeCollapsed, ScrimFadeExpanded, progress)
    // Expanded keeps the collapsed see-through look; lists without a collapsed scrim fade it in.
    val solidAlpha = if (collapsedScrim) ScrimAlpha else ScrimAlpha * progress
    val labelAlpha = ((progress - 0.5f) / 0.5f).coerceIn(0f, 1f).let { it * it }
    var selfTop by remember { mutableFloatStateOf(0f) }
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    Box(modifier.fillMaxHeight()) {
        // Gradient scrim over the content: not focusable, handles no keys, only draws.
        Box(
            Modifier
                .fillMaxHeight()
                .width(scrimWidth)
                .onGloballyPositioned { selfTop = it.positionInRoot().y }
                .drawBehind {
                    if (solidAlpha <= 0f) return@drawBehind
                    val top = (links.scrimTop - selfTop).coerceIn(0f, size.height)
                    val solid = (width.toPx() / size.width).coerceIn(0f, 1f)
                    val c = AppColors.Background
                    val brush = Brush.horizontalGradient(
                        0f to c.copy(alpha = solidAlpha),
                        solid to c.copy(alpha = solidAlpha),
                        (solid + (1f - solid) * 0.45f) to c.copy(alpha = solidAlpha * 0.45f),
                        1f to c.copy(alpha = 0f),
                        endX = size.width,
                    )
                    drawRect(brush, topLeft = Offset(0f, top), size = Size(size.width, size.height - top))
                },
        )
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(width)
                .padding(horizontal = 4.dp)
                .onFocusChanged { expanded = it.hasFocus },
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        ) {
            Items.forEachIndexed { index, item ->
                val isSelected = item.tab == selected
                Surface(
                    onClick = {
                        onSelect(item.tab)
                        // Close the rail: hand focus to the new tab's content once it can take it.
                        scope.launch {
                            repeat(20) {
                                delay(50)
                                if (!expanded || focusManager.moveFocus(FocusDirection.Right)) return@launch
                            }
                        }
                    },
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
                        if (labelAlpha > 0f) {
                            // Measured at full width (no reflow while the item grows); fades and slides in.
                            Text(
                                item.label,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier
                                    .padding(start = 12.dp)
                                    .wrapContentWidth(Alignment.Start, unbounded = true)
                                    .graphicsLayer {
                                        alpha = labelAlpha
                                        translationX = (1f - labelAlpha) * -8.dp.toPx()
                                    },
                            )
                        }
                    }
                }
            }
        }
    }
}
