package dev.gamspi.videogallery.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.gamspi.videogallery.ui.components.CatMascot
import dev.gamspi.videogallery.ui.components.CatPose
import dev.gamspi.videogallery.ui.components.ErrorView
import dev.gamspi.videogallery.ui.components.MovieCard
import dev.gamspi.videogallery.ui.components.ShimmerHost
import dev.gamspi.videogallery.ui.components.SkeletonGrid

@Composable
fun SearchScreen(
    onOpenMovie: (Int) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf("") }
    val fieldFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    // Survives navigation: the last focused result card. Plain holder, so focus moves
    // do not recompose the grid items.
    val memory = rememberSaveable(saver = SearchFocusMemory.Saver) { SearchFocusMemory() }
    // Card to restore for this appearance, captured once.
    val restore = remember { SearchRestore(memory.movieId) }
    val openMovie by rememberUpdatedState(onOpenMovie)
    val gridState = rememberLazyGridState()
    val firstCardFocus = remember { FocusRequester() }
    // First card if composed, else the nearest focusable below.
    fun focusFirstCard() {
        runCatching { firstCardFocus.requestFocus() }
            .onFailure { focusManager.moveFocus(FocusDirection.Down) }
    }
    LaunchedEffect(Unit) {
        viewModel.onQueryChange(text) // restore the query after process/back-stack restore
        // No card to restore (first entry, or return from Settings/Error): focus the field.
        if (restore.movieId == -1) fieldFocus.requestFocus()
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(top = 48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                viewModel.onQueryChange(it)
            },
            label = { Text("Поиск фильмов") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusFirstCard() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp).focusRequester(fieldFocus).onPreviewKeyEvent {
                // The field swallows DPAD_DOWN; send it to the first result instead.
                if (it.key == Key.DirectionDown && state is SearchUiState.Results) {
                    if (it.type == KeyEventType.KeyDown) focusFirstCard()
                    true
                } else {
                    false
                }
            },
        )
        ShimmerHost {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            // Non-grid states keep the screen gutter; the grid puts it into contentPadding.
            val gutter = Modifier.padding(start = 48.dp, end = 48.dp, bottom = 48.dp)
            when (val s = state) {
                SearchUiState.Idle -> Text("Введите название фильма")
                SearchUiState.Loading -> Box(Modifier.fillMaxSize().then(gutter), Alignment.TopStart) { SkeletonGrid(5, 2) }
                SearchUiState.Empty -> CatMascot(CatPose.Shrug, modifier = gutter, size = 120.dp, message = "Ничего не найдено")
                is SearchUiState.Error -> Box(gutter) {
                    ErrorView(
                        s.kind,
                        onRetry = viewModel::retry,
                        onOpenSettings = onOpenSettings,
                        autoFocus = false, // do not steal focus from the text field while typing
                        catSize = 88.dp,
                    )
                }
                is SearchUiState.Results -> LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(5),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 12.dp, bottom = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(s.movies, key = { it.id }) { movie ->
                        val ownRequester = remember { FocusRequester() }
                        val requester = if (movie.id == s.movies.first().id) firstCardFocus else ownRequester
                        if (movie.id == restore.movieId && !restore.handled) {
                            // The card is composed and attached by the time this runs (after first frame).
                            LaunchedEffect(Unit) {
                                withFrameNanos { }
                                restore.handled = true
                                requester.requestFocus()
                            }
                        }
                        val cardModifier = remember(requester, movie.id) {
                            Modifier
                                .focusRequester(requester)
                                .onFocusChanged { if (it.hasFocus) memory.movieId = movie.id }
                        }
                        val onClick = remember(movie.id) { { openMovie(movie.id) } }
                        MovieCard(movie = movie, onClick = onClick, modifier = cardModifier)
                    }
                }
            }
        }
        }
    }
}

/** Last focused result card; plain fields so focus writes stay outside the snapshot system. */
private class SearchFocusMemory(var movieId: Int = -1) {
    companion object {
        val Saver = listSaver<SearchFocusMemory, Int>(
            save = { listOf(it.movieId) },
            restore = { SearchFocusMemory(it[0]) },
        )
    }
}

/** Card to refocus once per appearance. */
private class SearchRestore(val movieId: Int) {
    var handled = false
}
