package dev.gamspi.videogallery.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.gamspi.videogallery.catalog.model.MovieDetails
import dev.gamspi.videogallery.catalog.model.isComingSoon
import dev.gamspi.videogallery.catalog.model.formatReleaseDate
import dev.gamspi.videogallery.watchlist.text
import java.time.LocalDate
import kotlinx.serialization.json.JsonPrimitive
import dev.gamspi.videogallery.history.PlaybackMeta
import dev.gamspi.videogallery.stream.EpisodeRef
import dev.gamspi.videogallery.ui.components.ErrorView
import dev.gamspi.videogallery.ui.components.FullScreenLoader
import dev.gamspi.videogallery.ui.components.AccentButton

@Composable
fun DetailsScreen(
    onOpenKeySettings: () -> Unit,
    onOpenSettings: () -> Unit,
    onWatch: () -> Unit,
    onStream: (releaseTitle: String, meta: PlaybackMeta) -> Unit = { _, _ -> },
    onPickRelease: (season: Int, episode: Int?) -> Unit = { _, _ -> },
    viewModel: DetailsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listSnapshot by viewModel.listSnapshot.collectAsStateWithLifecycle()
    val listError by viewModel.listError.collectAsStateWithLifecycle()
    val actions: @Composable () -> Unit = {
        val record = remember(state, listSnapshot) { viewModel.listRecord() }
        val season = (state as? DetailsUiState.Content)?.takeIf { it.series }?.selectedSeason
        record?.let {
            dev.gamspi.videogallery.watchlist.ListActionButton(it, ratingSeason = season, rateSeason = viewModel::rateSeason, change = viewModel::changeList)
            if ((state as? DetailsUiState.Content)?.details?.movie?.isComingSoon(LocalDate.now()) != true) {
                dev.gamspi.videogallery.watchlist.RateButton(it, season, viewModel::rateSeason, viewModel::changeList)
            }
        }
        listError?.let { Text(it) }
    }
    val upcomingActions: @Composable (Modifier) -> Unit = { modifier ->
        val record = remember(state, listSnapshot) { viewModel.listRecord() }
        val saved = record?.let { listSnapshot.records[it.identity]?.takeUnless { row -> row.deleted } }
        AccentButton(onClick = { viewModel.changeList("status", JsonPrimitive("unwatched")) }, modifier = modifier,
            selected = saved?.text("status") == "unwatched") {
            Text("К просмотру")
        }
        if ((state as? DetailsUiState.Content)?.series == true) {
            val notify = record?.text("notify") == "true"
            AccentButton(onClick = { viewModel.changeList("notify", JsonPrimitive(!notify)) }, selected = notify) {
                Text(if (notify) "Уведомлять ✓" else "Уведомлять")
            }
        }
    }
    when (val s = state) {
        DetailsUiState.Loading -> FullScreenLoader()
        is DetailsUiState.Error -> ErrorView(s.kind, onRetry = viewModel::retry, onOpenKeySettings = onOpenKeySettings, onOpenSettings = onOpenSettings)
        is DetailsUiState.Content -> if (!s.series) {
            DetailsContent(s.details, { if (viewModel.canWatch()) onWatch() }, actions, upcomingActions)
        } else {
            val play = { ref: EpisodeRef ->
                when (val t = viewModel.playTarget(ref)) {
                    PlayTarget.Unavailable -> Unit
                    is PlayTarget.Stream -> onStream(t.releaseTitle, t.meta)
                    is PlayTarget.PickRelease -> onPickRelease(ref.season, ref.episode)
                }
            }
            SeriesDetailsContent(
                s,
                onSelectSeason = viewModel::selectSeason,
                onRetryEpisodes = viewModel::retryEpisodes,
                onPlay = play,
                onPickSeason = { season -> if (viewModel.canPickSeason(season)) onPickRelease(season, viewModel.releaseEpisode(season)) },
                listActions = actions,
                upcomingActions = upcomingActions,
                onMarkEpisode = viewModel::markEpisode,
                onMarkSeason = viewModel::markSeason,
            )
        }
    }
}

@Composable
private fun DetailsContent(details: MovieDetails, onWatch: () -> Unit, listActions: @Composable () -> Unit,
    upcomingActions: @Composable (Modifier) -> Unit) {
    val movie = details.movie
    val watchFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { watchFocus.requestFocus() }
    }
    Box(Modifier.fillMaxSize()) {
        if (details.backdropUrl != null) {
            AsyncImage(
                model = details.backdropUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)))
        Row(
            modifier = Modifier.fillMaxSize().padding(48.dp),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            val poster = Modifier.width(240.dp).aspectRatio(2f / 3f)
            if (movie.posterUrl != null) {
                AsyncImage(
                    model = movie.posterUrl,
                    contentDescription = movie.title,
                    contentScale = ContentScale.Crop,
                    modifier = poster,
                )
            } else {
                Box(
                    modifier = poster.background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(movie.title, style = MaterialTheme.typography.titleMedium)
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(movie.title, style = MaterialTheme.typography.headlineLarge)
                if (movie.originalTitle.isNotBlank() && movie.originalTitle != movie.title) {
                    Text(movie.originalTitle, style = MaterialTheme.typography.titleMedium)
                }
                val meta = listOfNotNull(movie.year?.toString(), formatRuntime(details.runtimeMin))
                if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodyLarge)
                formatReleaseDate(movie.premiereDate)?.let { Text("Дата выхода: $it", style = MaterialTheme.typography.bodyLarge) }
                    ?: movie.year?.takeIf { movie.isComingSoon(LocalDate.now()) }?.let { Text("Дата выхода: $it") }
                if (details.genres.isNotEmpty()) {
                    Text(details.genres.joinToString(", "), style = MaterialTheme.typography.bodyLarge)
                }
                if (movie.overview.isNotBlank()) {
                    Text(movie.overview, style = MaterialTheme.typography.bodyMedium, maxLines = 8, overflow = TextOverflow.Ellipsis)
                }
                if (movie.isComingSoon(LocalDate.now())) {
                    upcomingActions(Modifier.focusRequester(watchFocus))
                } else AccentButton(
                    onClick = onWatch,
                    modifier = Modifier.focusRequester(watchFocus),
                ) {
                    Text("Смотреть")
                }
                listActions()
            }
        }
    }
}
