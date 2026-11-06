package dev.gamspi.videogallery.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.gamspi.videogallery.ui.components.IconPillButton
import dev.gamspi.videogallery.ui.icons.AppIcons
import dev.gamspi.videogallery.ui.theme.AppColors
import kotlinx.coroutines.delay

/**
 * Bottom panel: title, progress, then play/pause and settings buttons.
 * The buttons take focus only while [buttonsFocused], so menu and root keep their keys otherwise.
 */
@Composable
fun PlayerOverlay(
    title: String,
    positionMs: Long,
    durationMs: Long,
    playWhenReady: Boolean,
    buffering: Boolean,
    buttonsFocused: Boolean,
    onTogglePlay: () -> Unit,
    onOpenMenu: () -> Unit,
    playButtonFocus: FocusRequester,
    settingsButtonFocus: FocusRequester,
    statusLine: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Color(0xB3000000))
            .padding(horizontal = 48.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, color = Color.White)
        val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        Box(Modifier.fillMaxWidth().height(4.dp).background(Color(0x66FFFFFF))) {
            Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(AppColors.Accent))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val canFocus = Modifier.focusProperties { this.canFocus = buttonsFocused }
            IconPillButton(
                icon = if (playWhenReady) AppIcons.Pause else AppIcons.Play,
                label = if (playWhenReady) "Пауза" else "Смотреть",
                onClick = onTogglePlay,
                modifier = Modifier.focusRequester(playButtonFocus).then(canFocus),
            )
            Spacer(Modifier.width(12.dp))
            IconPillButton(
                icon = AppIcons.Settings,
                label = "Настройки",
                onClick = onOpenMenu,
                modifier = Modifier.focusRequester(settingsButtonFocus).then(canFocus),
            )
            Spacer(Modifier.weight(1f))
            val time = formatTime(positionMs, durationMs) +
                if (durationMs > 0) " / " + formatTime(durationMs, durationMs) else ""
            val shown = when {
                !playWhenReady -> "Пауза · $time"
                buffering -> "Загрузка… · $time"
                else -> time
            }
            Text(shown, color = Color.White)
        }
        statusLine?.let { Text(it, color = Color(0xFFBBBBBB)) }
    }
}

/**
 * Top scrim bar shown with the bottom panel: heading on the left, wall clock and the end time
 * on the right. Times tick in an effect once per second, so only this bar's texts recompose.
 * Not focusable: key handling stays with the player root.
 */
@Composable
fun PlayerTopBar(
    heading: String,
    positionMs: () -> Long,
    durationMs: Long,
    modifier: Modifier = Modifier,
) {
    val duration by rememberUpdatedState(durationMs)
    var clock by remember { mutableStateOf(formatClock(System.currentTimeMillis())) }
    var endsAt by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            val now = System.currentTimeMillis()
            clock = formatClock(now)
            endsAt = playbackEndAt(now, positionMs(), duration)?.let(::formatClock)
            delay(1_000L)
        }
    }
    Row(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
            .padding(start = 48.dp, end = 48.dp, top = 24.dp, bottom = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            heading,
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(24.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(clock, color = Color.White, style = MaterialTheme.typography.titleMedium)
            endsAt?.let {
                Text("закончится в $it", color = Color(0xFFBBBBBB), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
