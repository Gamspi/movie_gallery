package dev.gamspi.videogallery.releases

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gamspi.videogallery.history.PlaybackMeta
import dev.gamspi.videogallery.ui.components.AccentButton
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.gamspi.videogallery.torrents.Quality
import dev.gamspi.videogallery.torrents.TorrentResult
import dev.gamspi.videogallery.torrents.ProviderId
import dev.gamspi.videogallery.torrents.UnavailableReason
import dev.gamspi.videogallery.torrents.formatSize
import dev.gamspi.videogallery.torrents.key
import dev.gamspi.videogallery.torrents.needsResolve
import dev.gamspi.videogallery.ui.components.CatMascot
import dev.gamspi.videogallery.ui.components.CatPose
import dev.gamspi.videogallery.ui.components.ErrorView
import dev.gamspi.videogallery.ui.components.FullScreenLoader

fun releaseInfoLine(r: TorrentResult): String =
    listOfNotNull(
        r.quality?.label,
        formatSize(r.sizeBytes),
        "↑${r.seeders} ↓${r.leechers}",
        r.provider.label,
    ).joinToString(" · ")

/** Per-provider explanation for a provider that gave no results. */
fun unavailableNote(id: ProviderId, reason: UnavailableReason?): String = when (reason) {
    UnavailableReason.Captcha -> "${id.label} просит капчу — войди один раз в браузере"
    UnavailableReason.BadCredentials -> "${id.label}: неверный логин или пароль"
    UnavailableReason.NoCredentials -> "${id.label}: укажи логин и пароль в настройках"
    UnavailableReason.Unavailable, null -> "${id.label} недоступен"
}

@Composable
fun ReleasesScreen(
    onOpenCatalogSettings: () -> Unit,
    onOpenSettings: () -> Unit,
    onPlay: (TorrentResult, PlaybackMeta) -> Unit,
    viewModel: ReleasesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val play by viewModel.play.collectAsStateWithLifecycle()
    LaunchedEffect(play) {
        val request = play ?: return@LaunchedEffect
        viewModel.onPlayHandled()
        onPlay(request.release, request.meta)
    }
    when (val s = state) {
        ReleasesUiState.Loading -> FullScreenLoader("Ищем раздачи…")
        is ReleasesUiState.Error -> ErrorView(s.kind, onRetry = viewModel::retry, onOpenCatalogSettings = onOpenCatalogSettings, onOpenSettings = onOpenSettings)
        is ReleasesUiState.Content -> ReleasesContent(s, viewModel::setFilter) {
            // Releases without a magnet are resolved first; the rest open directly.
            if (it.needsResolve) viewModel.resolve(it) else onPlay(it, s.metaFor(it))
        }
    }
}

@Composable
private fun ReleasesContent(
    content: ReleasesUiState.Content,
    onFilter: (Quality?) -> Unit,
    onPlay: (TorrentResult) -> Unit,
) {
    val allFocus = remember { FocusRequester() }
    val restoreFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val visible = content.visible
    // Survive navigation: the last focused release.
    var focusedKey by rememberSaveable { mutableStateOf("") }
    val restoreIndex = visible.indexOfFirst { it.key == focusedKey }.coerceAtLeast(0)
    // Initial focus only: last focused row, else first row, otherwise the «Все» filter.
    LaunchedEffect(Unit) {
        if (visible.isNotEmpty()) listState.scrollToItem(restoreIndex)
        withFrameNanos { }
        runCatching { if (visible.isNotEmpty()) restoreFocus.requestFocus() else allFocus.requestFocus() }
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(content.movieTitle, style = MaterialTheme.typography.headlineMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            FilterButton("Все", content.filter == null, Modifier.focusRequester(allFocus)) { onFilter(null) }
            Quality.entries.forEach { q ->
                FilterButton(q.label, content.filter == q, Modifier) { onFilter(q) }
            }
        }
        if (content.unavailable.isNotEmpty()) {
            Text(
                content.unavailable.joinToString(", ") { unavailableNote(it, content.reasons[it]) },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        content.message?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFFF8A80))
        }
        if (visible.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                CatMascot(
                    CatPose.Shrug,
                    size = 120.dp,
                    message = if (content.noProviders) "Включите трекеры в настройках" else "Раздачи не найдены",
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(visible, key = { _, r -> r.key }) { index, r ->
                    val resolving = content.resolving == r.key
                    ListItem(
                        selected = false,
                        onClick = { onPlay(r) },
                        headlineContent = { Text(r.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(if (resolving) "Открываем раздачу…" else releaseInfoLine(r))
                        },
                        modifier = Modifier.fillMaxWidth()
                            .then(if (index == restoreIndex) Modifier.focusRequester(restoreFocus) else Modifier)
                            .onFocusChanged { if (it.hasFocus) focusedKey = r.key },
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    AccentButton(
        onClick = onClick,
        selected = selected,
        modifier = modifier,
    ) { Text(if (selected) "✓ $label" else label) }
}
