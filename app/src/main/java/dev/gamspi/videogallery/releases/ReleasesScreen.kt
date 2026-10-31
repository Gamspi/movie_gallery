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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.gamspi.videogallery.torrents.Quality
import dev.gamspi.videogallery.torrents.TorrentResult
import dev.gamspi.videogallery.torrents.formatSize
import dev.gamspi.videogallery.ui.components.ErrorView

fun releaseInfoLine(r: TorrentResult): String =
    listOfNotNull(
        r.quality?.label,
        formatSize(r.sizeBytes),
        "↑${r.seeders} ↓${r.leechers}",
        r.provider.label,
    ).joinToString(" · ")

@Composable
fun ReleasesScreen(
    onOpenSettings: () -> Unit,
    onPlay: (TorrentResult, String?) -> Unit,
    viewModel: ReleasesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val s = state) {
        ReleasesUiState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Ищем раздачи…") }
        is ReleasesUiState.Error -> ErrorView(s.kind, onRetry = viewModel::retry, onOpenSettings = onOpenSettings)
        is ReleasesUiState.Content -> ReleasesContent(s, viewModel::setFilter) { onPlay(it, s.imdbId) }
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
    var focusedHash by rememberSaveable { mutableStateOf("") }
    val restoreIndex = visible.indexOfFirst { it.infoHash == focusedHash }.coerceAtLeast(0)
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
                content.unavailable.joinToString(", ") { "${it.label} недоступен" },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (visible.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(
                    if (content.noProviders) "Включите трекеры в настройках" else "Раздачи не найдены",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(visible, key = { _, r -> r.infoHash }) { index, r ->
                    ListItem(
                        selected = false,
                        onClick = { onPlay(r) },
                        headlineContent = { Text(r.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(releaseInfoLine(r)) },
                        modifier = Modifier.fillMaxWidth()
                            .then(if (index == restoreIndex) Modifier.focusRequester(restoreFocus) else Modifier)
                            .onFocusChanged { if (it.hasFocus) focusedHash = r.infoHash },
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = if (selected) {
            ButtonDefaults.colors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            ButtonDefaults.colors()
        },
        modifier = modifier,
    ) { Text(if (selected) "✓ $label" else label) }
}
