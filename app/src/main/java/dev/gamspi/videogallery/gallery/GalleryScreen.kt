package dev.gamspi.videogallery.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.gamspi.videogallery.ui.components.ErrorView
import dev.gamspi.videogallery.ui.components.FullScreenLoader
import dev.gamspi.videogallery.ui.components.IconPillButton
import dev.gamspi.videogallery.ui.components.MovieCard
import dev.gamspi.videogallery.ui.components.ShimmerHost
import dev.gamspi.videogallery.ui.components.SkeletonBar
import dev.gamspi.videogallery.ui.components.SkeletonRow
import dev.gamspi.videogallery.ui.icons.AppIcons

@Composable
fun GalleryScreen(
    onOpenMovie: (Int) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCatalogSettings: () -> Unit,
    viewModel: GalleryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val s = state) {
        is GalleryUiState.Loading ->
            if (s.initial) FullScreenLoader() else GallerySkeleton()
        is GalleryUiState.Error ->
            ErrorView(s.kind, onRetry = viewModel::retry, onOpenSettings = onOpenCatalogSettings)
        is GalleryUiState.Content -> GalleryContent(s, onOpenMovie, onOpenSearch, onOpenSettings)
    }
}

// Reload placeholder: row titles and card skeletons.
@Composable
private fun GallerySkeleton() {
    ShimmerHost {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            repeat(3) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.width(220.dp)) { SkeletonBar(widthFraction = 1f, height = 24.dp) }
                    SkeletonRow()
                }
            }
        }
    }
}

/**
 * Last focused card, kept outside the snapshot system: focus moves write it on every D-pad
 * press, and a snapshot read in card composition would recompose every visible card.
 */
private class GalleryFocusMemory(
    var row: Int = -1,
    var movieId: Int = -1,
    var initialFocusDone: Boolean = false,
) {
    // Per appearance (not saved): header DPAD_DOWN target.
    var lastFocused: FocusRequester? = null
    var firstCardAttached = false

    companion object {
        val Saver = listSaver<GalleryFocusMemory, Int>(
            save = { listOf(it.row, it.movieId, if (it.initialFocusDone) 1 else 0) },
            restore = { GalleryFocusMemory(it[0], it[1], it[2] == 1) },
        )
    }
}

/** Card to focus once per appearance, captured when the content enters composition. */
private class RestoreTarget(private val row: Int, private val movieId: Int, private val firstCard: Boolean) {
    var handled = false

    fun matches(rowIndex: Int, index: Int, id: Int): Boolean =
        if (movieId != -1) rowIndex == row && id == movieId else firstCard && rowIndex == 0 && index == 0
}

@Composable
private fun GalleryContent(
    content: GalleryUiState.Content,
    onOpenMovie: (Int) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    // Survives navigation (saved with the back stack entry).
    val memory = rememberSaveable(saver = GalleryFocusMemory.Saver) { GalleryFocusMemory() }
    val restore = remember { RestoreTarget(memory.row, memory.movieId, !memory.initialFocusDone) }
    val firstCard = remember { FocusRequester() }
    val openMovie by rememberUpdatedState(onOpenMovie)
    val listState = rememberLazyListState()
    // Down from the header: previously focused card, else the first card, else default search.
    val headerFocus = remember {
        Modifier.focusProperties {
            down = memory.lastFocused ?: if (memory.firstCardAttached) firstCard else FocusRequester.Default
        }
    }

    ShimmerHost {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Leading slot: logo is inserted here later.
                    Text(
                        "VideoGallery",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconPillButton(AppIcons.Search, "Поиск", onOpenSearch, headerFocus)
                    IconPillButton(AppIcons.Settings, "Настройки", onOpenSettings, headerFocus)
                    HeaderClock()
                }
            }
        }
        itemsIndexed(content.rows, key = { index, _ -> "row-$index" }) { rowIndex, row ->
            // Saved per row (inside the lazy item), so horizontal scroll position is restored too.
            val rowState = rememberLazyListState()
            Column {
                Text(
                    row.title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 48.dp),
                )
                LazyRow(
                    state = rowState,
                    contentPadding = PaddingValues(horizontal = 48.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    itemsIndexed(row.movies, key = { _, m -> m.id }) { index, movie ->
                        val isFirst = rowIndex == 0 && index == 0
                        val ownRequester = remember { FocusRequester() }
                        val requester = if (isFirst) firstCard else ownRequester
                        if (!restore.handled && restore.matches(rowIndex, index, movie.id)) {
                            // The card is composed and attached by the time this runs (after first frame).
                            LaunchedEffect(Unit) {
                                withFrameNanos { }
                                restore.handled = true
                                memory.initialFocusDone = true
                                requester.requestFocus()
                            }
                        }
                        DisposableEffect(requester, isFirst) {
                            if (isFirst) memory.firstCardAttached = true
                            onDispose {
                                if (isFirst) memory.firstCardAttached = false
                                if (memory.lastFocused === requester) memory.lastFocused = null
                            }
                        }
                        val cardModifier = remember(requester, rowIndex, movie.id) {
                            Modifier
                                .focusRequester(requester)
                                .onFocusChanged {
                                    if (it.hasFocus) {
                                        memory.row = rowIndex
                                        memory.movieId = movie.id
                                        memory.lastFocused = requester
                                    }
                                }
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
