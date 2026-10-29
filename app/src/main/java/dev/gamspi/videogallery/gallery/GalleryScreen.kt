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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    val state by viewModel.state.collectAsStateWithLifecycle()
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
    // Survive navigation (saved with the back stack entry): last focused card and one-time initial focus.
    var focusedRow by rememberSaveable { mutableIntStateOf(-1) }
    var focusedMovieId by rememberSaveable { mutableIntStateOf(-1) }
    var initialFocusDone by rememberSaveable { mutableStateOf(false) }
    // Per composition: was the focus request for this appearance already issued.
    var restoreHandled by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LazyColumn(
        state = listState,
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
        itemsIndexed(content.rows, key = { index, _ -> "row-$index" }) { rowIndex, row ->
            // Saved per row (inside the lazy item), so horizontal scroll position is restored too.
            val rowState = rememberLazyListState()
            Column {
                Text(
                    row.title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                LazyRow(state = rowState, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    itemsIndexed(row.movies, key = { _, m -> m.id }) { index, movie ->
                        val saved = focusedMovieId != -1
                        val isTarget = if (saved) {
                            rowIndex == focusedRow && movie.id == focusedMovieId
                        } else {
                            !initialFocusDone && rowIndex == 0 && index == 0
                        }
                        val requester = remember { FocusRequester() }
                        if (isTarget && !restoreHandled) {
                            // The card is composed and attached by the time this runs (after first frame).
                            LaunchedEffect(Unit) {
                                withFrameNanos { }
                                restoreHandled = true
                                initialFocusDone = true
                                requester.requestFocus()
                            }
                        }
                        MovieCard(
                            movie = movie,
                            onClick = { onOpenMovie(movie.id) },
                            modifier = Modifier
                                .focusRequester(requester)
                                .onFocusChanged {
                                    if (it.hasFocus) {
                                        focusedRow = rowIndex
                                        focusedMovieId = movie.id
                                    }
                                },
                        )
                    }
                }
            }
        }
    }
}
