package dev.gamspi.videogallery.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import dev.gamspi.videogallery.ui.components.ErrorView
import dev.gamspi.videogallery.ui.components.MovieCard

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
    LaunchedEffect(Unit) {
        viewModel.onQueryChange(text) // restore the query after process/back-stack restore
        fieldFocus.requestFocus()
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(48.dp),
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
            keyboardActions = KeyboardActions(onSearch = { focusManager.moveFocus(FocusDirection.Down) }),
            modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus),
        )
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            when (val s = state) {
                SearchUiState.Idle -> Text("Введите название фильма")
                SearchUiState.Loading -> Text("Загрузка…")
                SearchUiState.Empty -> Text("Ничего не найдено", style = MaterialTheme.typography.headlineMedium)
                is SearchUiState.Error ->
                    ErrorView(s.kind, onRetry = viewModel::retry, onOpenSettings = onOpenSettings)
                is SearchUiState.Results -> LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(s.movies, key = { it.id }) { movie ->
                        MovieCard(movie = movie, onClick = { onOpenMovie(movie.id) })
                    }
                }
            }
        }
    }
}
