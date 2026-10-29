package dev.gamspi.videogallery.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.gamspi.videogallery.catalog.model.MovieDetails
import dev.gamspi.videogallery.ui.components.ErrorView

@Composable
fun DetailsScreen(
    onOpenSettings: () -> Unit,
    viewModel: DetailsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val s = state) {
        DetailsUiState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Загрузка…") }
        is DetailsUiState.Error -> ErrorView(s.kind, onRetry = viewModel::retry, onOpenSettings = onOpenSettings)
        is DetailsUiState.Content -> DetailsContent(s.details)
    }
}

@Composable
private fun DetailsContent(details: MovieDetails) {
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
                if (details.genres.isNotEmpty()) {
                    Text(details.genres.joinToString(", "), style = MaterialTheme.typography.bodyLarge)
                }
                if (movie.overview.isNotBlank()) {
                    Text(movie.overview, style = MaterialTheme.typography.bodyMedium, maxLines = 8)
                }
                // Stage 1: no playback yet. Enabled-but-no-op so it can hold default focus
                // (a disabled TV Button is not focusable); dimmed to look inactive.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Button(
                        onClick = {},
                        modifier = Modifier.focusRequester(watchFocus).alpha(0.5f),
                    ) {
                        Text("Смотреть")
                    }
                    Text("Скоро", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
