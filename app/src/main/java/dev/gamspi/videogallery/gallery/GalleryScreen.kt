package dev.gamspi.videogallery.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.gamspi.videogallery.ui.components.ErrorView
import dev.gamspi.videogallery.ui.components.MovieCard

@Composable
fun GalleryScreen(
    onOpenMovie: (Int) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: GalleryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    when (val s = state) {
        GalleryUiState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Загрузка…") }
        is GalleryUiState.Error ->
            ErrorView(s.kind, onRetry = viewModel::retry, onOpenSettings = onOpenSettings)
        is GalleryUiState.Content -> GalleryContent(s, onOpenMovie, onOpenSearch, onOpenSettings)
    }
}

@Composable
private fun GalleryContent(
    content: GalleryUiState.Content,
    onOpenMovie: (Int) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val firstCardFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstCardFocus.requestFocus() } }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = onOpenSearch) { Text("Поиск") }
                Button(onClick = onOpenSettings) { Text("Настройки") }
            }
        }
        content.rows.forEachIndexed { rowIndex, row ->
            item(key = "row-$rowIndex") {
                Column {
                    Text(
                        row.title,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(row.movies, key = { it.id }) { movie ->
                            val isFirst = rowIndex == 0 && movie == row.movies.first()
                            MovieCard(
                                movie = movie,
                                onClick = { onOpenMovie(movie.id) },
                                modifier = if (isFirst) Modifier.focusRequester(firstCardFocus) else Modifier,
                            )
                        }
                    }
                }
            }
        }
    }
}
