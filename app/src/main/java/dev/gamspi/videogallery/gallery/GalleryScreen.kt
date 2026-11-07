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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.gamspi.videogallery.R
import dev.gamspi.videogallery.catalog.model.MediaKind
import dev.gamspi.videogallery.catalog.model.Movie
import dev.gamspi.videogallery.history.WatchEntry
import dev.gamspi.videogallery.ui.components.CatLogo
import dev.gamspi.videogallery.ui.components.ErrorView
import dev.gamspi.videogallery.ui.components.FullScreenLoader
import dev.gamspi.videogallery.ui.components.IconPillButton
import dev.gamspi.videogallery.ui.components.MovieCard
import dev.gamspi.videogallery.ui.components.LocalCatalogSource
import dev.gamspi.videogallery.ui.components.ShimmerHost
import dev.gamspi.videogallery.ui.components.SkeletonBar
import dev.gamspi.videogallery.ui.components.SkeletonRow
import dev.gamspi.videogallery.ui.icons.AppIcons

@Composable
fun GalleryScreen(
    onOpenMovie: (Int, MediaKind) -> Unit,
    onContinue: (WatchEntry) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenKeySettings: () -> Unit,
    viewModel: GalleryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val source = LocalCatalogSource.current
    val visibleHistory = remember(history, source) { historyForSource(history, source.name) }
    val tab by viewModel.tab.collectAsStateWithLifecycle()
    // Survives navigation and process death; the ViewModel starts on All and is told the saved tab.
    var savedTab by rememberSaveable { mutableStateOf(HomeTab.All) }
    LaunchedEffect(Unit) { viewModel.selectTab(savedTab) }
    // Focus memory lives here so it outlives the content swapping to a skeleton on tab change.
    val memory = rememberSaveable(saver = GalleryFocusMemory.Saver) { GalleryFocusMemory() }
    val railEntry = remember { FocusRequester() }
    val railLinks = remember { RailLinks() }
    val showRail = !(state is GalleryUiState.Loading && (state as GalleryUiState.Loading).initial)
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().padding(start = if (showRail) RailCollapsedWidth else 0.dp)) {
            when (val s = state) {
                is GalleryUiState.Loading ->
                    if (s.initial) FullScreenLoader() else GallerySkeleton()
                is GalleryUiState.Error ->
                    ErrorView(s.kind, onRetry = viewModel::retry, onOpenKeySettings = onOpenKeySettings, onOpenSettings = onOpenSettings)
                is GalleryUiState.Content -> key(s.tab) {
                    GalleryContent(s, visibleHistory, memory, railEntry, railLinks, onOpenMovie, onContinue, onOpenSearch, onOpenSettings)
                }
            }
        }
        if (showRail) {
            HomeRail(
                selected = tab,
                onSelect = {
                    if (it != tab) {
                        // Focus stays on the rail: forget the card and skip the initial auto-focus.
                        memory.rowKey = ""
                        memory.movieId = -1
                        memory.initialFocusDone = true
                        savedTab = it
                        viewModel.selectTab(it)
                    }
                },
                entry = railEntry,
                links = railLinks,
                modifier = Modifier.align(Alignment.CenterStart),
            )
        }
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
internal class GalleryFocusMemory(
    var rowKey: String = "",
    var movieId: Int = -1,
    var initialFocusDone: Boolean = false,
) {
    // Per appearance (not saved): header DPAD_DOWN target.
    var lastFocused: FocusRequester? = null
    var firstCardAttached = false
    // A card, not the header, holds focus.
    var cardFocused = false

    companion object {
        val Saver = listSaver<GalleryFocusMemory, Any>(
            save = { listOf(it.rowKey, it.movieId, it.initialFocusDone) },
            restore = { GalleryFocusMemory(it[0] as String, it[1] as Int, it[2] as Boolean) },
        )
    }
}

/**
 * Card to focus once per appearance, or again when the focused continue card leaves the history.
 * [target] is re-resolved on each composition while [active], with fallback to the row's first card.
 */
private class RestoreTarget(var wanted: CardKey?, var active: Boolean) {
    var target: CardKey? = null

    fun matches(rowKey: String, id: Int): Boolean =
        active && target.let { it != null && it.row == rowKey && it.movieId == id }
}

/** Card identity within a row: TMDB movie and series ids can collide, so series ids get a high bit. */
private fun Movie.cardId(): Int = if (kind == MediaKind.Series) id or SERIES_ID_BIT else id

private const val SERIES_ID_BIT = 1 shl 30

/** One displayed row: the continue row or a catalog row. */
private class DisplayRow(val key: String, val title: String, val movies: List<Movie>)

@Composable
private fun GalleryContent(
    content: GalleryUiState.Content,
    history: List<WatchEntry>,
    memory: GalleryFocusMemory,
    railEntry: FocusRequester,
    railLinks: RailLinks,
    onOpenMovie: (Int, MediaKind) -> Unit,
    onContinue: (WatchEntry) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val restore = remember {
        val saved = if (memory.movieId != -1) CardKey(memory.rowKey, memory.movieId) else null
        RestoreTarget(saved, active = saved != null || !memory.initialFocusDone)
    }
    val firstCard = remember { FocusRequester() }
    val openMovie by rememberUpdatedState(onOpenMovie)
    val continueWatch by rememberUpdatedState(onContinue)
    val listState = rememberLazyListState()
    // Built once per history change, not per card.
    val entriesById = remember(history) { history.associateBy { it.kind to it.movieId } }
    val historyCardIds = remember(history) { history.map { it.toMovie().cardId() }.toSet() }
    val progress = remember(history) { progressByMovie(history) }
    val rows = remember(history, content) {
        buildList {
            if (history.isNotEmpty()) add(DisplayRow(CONTINUE_ROW_KEY, "Продолжить просмотр", history.map { it.toMovie() }))
            content.rows.forEachIndexed { i, row -> add(DisplayRow(catalogRowKey(i), row.title, row.movies)) }
        }
    }
    // The focused continue card vanished (watched to the end): refocus a neighbour.
    if (!restore.active && memory.cardFocused && memory.rowKey == CONTINUE_ROW_KEY && memory.movieId !in historyCardIds) {
        restore.wanted = CardKey(memory.rowKey, memory.movieId)
        restore.active = true
    }
    if (restore.active) restore.target = resolveFocusTarget(rows.map { r -> r.key to r.movies.map { it.cardId() } }, restore.wanted)
    // Down from the header: previously focused card, else the first card, else default search.
    val headerFocus = remember {
        Modifier
            .focusProperties {
                down = memory.lastFocused ?: if (memory.firstCardAttached) firstCard else FocusRequester.Default
            }
            .onFocusChanged { if (it.hasFocus) memory.cardFocused = false }
    }

    // Right from the rail: the remembered card, else the first one.
    DisposableEffect(railLinks) {
        railLinks.enter = { memory.lastFocused ?: if (memory.firstCardAttached) firstCard else FocusRequester.Default }
        onDispose { railLinks.enter = { FocusRequester.Default } }
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
                    CatLogo()
                    Text(
                        stringResource(R.string.app_name),
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
        itemsIndexed(rows, key = { _, row -> row.key }) { rowIndex, row ->
            // Saved per row (inside the lazy item), so horizontal scroll position is restored too.
            val rowState = rememberLazyListState()
            val isContinue = row.key == CONTINUE_ROW_KEY
            if (isContinue) KeepFocusedContinueCardVisible(row.movies, memory, rowState)
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
                    itemsIndexed(row.movies, key = { _, m -> m.cardId() }) { index, movie ->
                        val isFirst = rowIndex == 0 && index == 0
                        val ownRequester = remember { FocusRequester() }
                        val requester = if (isFirst) firstCard else ownRequester
                        if (restore.matches(row.key, movie.cardId())) {
                            // The card is composed and attached by the time this runs (after first frame).
                            LaunchedEffect(Unit) {
                                withFrameNanos { }
                                restore.active = false
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
                        val cardModifier = remember(requester, row.key, movie.id, movie.kind, index) {
                            Modifier
                                .focusRequester(requester)
                                // Left from the first card of any row enters the rail.
                                .then(if (index == 0) Modifier.focusProperties { left = railEntry } else Modifier)
                                .onFocusChanged {
                                    if (it.hasFocus) {
                                        memory.rowKey = row.key
                                        memory.movieId = movie.cardId()
                                        memory.lastFocused = requester
                                        memory.cardFocused = true
                                    }
                                }
                        }
                        val entry = if (isContinue) entriesById[movie.kind to movie.id] else null
                        val onClick = remember(movie.id, movie.kind, entry) {
                            if (entry != null) { { continueWatch(entry) } } else { { openMovie(movie.id, movie.kind) } }
                        }
                        MovieCard(movie = movie, onClick = onClick, modifier = cardModifier, progress = progress[movie.kind to movie.id])
                    }
                }
            }
        }
    }
    }
}

/** History reordered under a focused continue card: scroll the row so the card stays on screen. */
@Composable
private fun KeepFocusedContinueCardVisible(movies: List<Movie>, memory: GalleryFocusMemory, rowState: LazyListState) {
    val ids = remember(movies) { movies.map { it.cardId() } }
    val previous = remember { arrayOf(ids) }
    LaunchedEffect(ids) {
        if (ids == previous[0]) return@LaunchedEffect
        previous[0] = ids
        val index = ids.indexOf(memory.movieId)
        if (memory.cardFocused && memory.rowKey == CONTINUE_ROW_KEY && index >= 0) {
            val visible = rowState.layoutInfo.visibleItemsInfo.any { it.index == index }
            if (!visible) rowState.scrollToItem(index)
        }
    }
}
