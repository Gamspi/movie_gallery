package dev.gamspi.videogallery.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.gamspi.videogallery.ui.components.AccentButton
import dev.gamspi.videogallery.watchlist.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement

/** Rail entry target: the selected row if placed on screen, else the first placed row, else none. */
internal fun <T> savedEntryTarget(selected: String?, order: List<String>, placed: Set<String>, targets: Map<String, T>): T? =
    (selected?.takeIf { it in placed } ?: order.firstOrNull { it in placed })?.let { targets[it] }

/** Row that takes focus after [removed] left the list: next surviving, else previous, else null. */
internal fun neighbourAfterRemoval(removed: String, before: List<String>, after: List<String>): String? {
    val index = before.indexOf(removed)
    if (index < 0) return after.firstOrNull()
    return before.drop(index + 1).firstOrNull { it in after }
        ?: before.take(index).lastOrNull { it in after }
        ?: after.firstOrNull()
}

private fun LazyListState.placedKeys(): Set<String> = layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String }.toSet()

// An unattached requester throws; the caller then falls back to the rail.
private fun FocusRequester.tryFocus(): Boolean = try { requestFocus() } catch (_: IllegalStateException) { false }

@Composable
internal fun SavedListContent(snapshot: ListSnapshot, watched: Boolean, error: String?, rail: FocusRequester,
    links: RailLinks, open: (ListRecord) -> Unit, change: (ListRecord, String, JsonElement) -> Unit) {
    val rows = remember(snapshot, watched) { listTitles(snapshot, if(watched) "watched" else "unwatched") }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val focuses = remember { mutableMapOf<String, FocusRequester>() }
    val listState = rememberLazyListState()
    val keys = remember(rows) { rows.map { it.titleKey } }
    val latestKeys by rememberUpdatedState(keys)
    // Only placed rows are focus targets: prefetched ones are composed but not on screen.
    DisposableEffect(links, keys, selected) {
        links.enter = { savedEntryTarget(selected, keys, listState.placedKeys(), focuses) ?: FocusRequester.Default }
        onDispose { links.enter = { FocusRequester.Default } }
    }
    // Row holding focus, kept while its dialog is open so a background removal still moves focus.
    var focusedKey by remember { mutableStateOf<String?>(null) }
    var dialogKey by remember { mutableStateOf<String?>(null) }
    var previousKeys by remember { mutableStateOf(keys) }
    val headers = 1 + (if (error != null) 1 else 0)
    LaunchedEffect(keys) {
        val before = previousKeys
        previousKeys = keys
        val lost = focusedKey?.takeIf { it !in keys } ?: return@LaunchedEffect
        focusedKey = null
        dialogKey = null
        focuses.remove(lost)
        val target = neighbourAfterRemoval(lost, before, keys)
        val moved = target != null && run {
            if (target !in listState.placedKeys()) listState.scrollToItem(keys.indexOf(target) + headers)
            withTimeoutOrNull(1_000) { snapshotFlow { target in listState.placedKeys() }.first { it } } != null
                && run { withFrameNanos { }; focuses[target]?.tryFocus() == true }
        }
        if (!moved) rail.tryFocus()
    }
    LazyColumn(Modifier.fillMaxSize().padding(32.dp), state = listState, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text(if(watched) "Просмотренные" else "К просмотру", style = MaterialTheme.typography.headlineMedium) }
        error?.let { item { Text(it) } }
        if(rows.isEmpty()) item { Text("В списке пока пусто. Добавьте фильм или сериал из его карточки.") }
        items(rows, key = { it.titleKey }) { row ->
            val focus = focuses.getOrPut(row.titleKey) { FocusRequester() }
            val key = row.titleKey
            val entry = Modifier.focusRequester(focus).onFocusChanged { if(it.isFocused) selected = key }
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                .onFocusChanged {
                    // A removed row also reports focus loss; that one must not clear the key.
                    if(it.hasFocus) focusedKey = key
                    else if(focusedKey == key && key in latestKeys && dialogKey != key) focusedKey = null
                }.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AsyncImage(model = row.text("poster_url"), contentDescription = null,
                    modifier = Modifier.width(72.dp).height(108.dp), contentScale = ContentScale.Crop)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(row.text("title_ru").ifBlank { row.text("title_en").ifBlank { "Без названия" } }, style = MaterialTheme.typography.titleLarge)
                    if(row.text("my_rating").isNotBlank()) Text("Моя оценка: ${row.text("my_rating")} / 10")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.focusProperties { left = rail }) {
                        val openable = savedTarget(row) != null
                        if(openable) {
                            AccentButton(onClick = { open(row) }, modifier = entry) { Text("Открыть") }
                        } else { Text("Карточка фильма недоступна") }
                        ListActionButton(row, if(openable) Modifier else entry,
                            onDialog = { dialogKey = if(it) key else null }) { field, value -> change(row, field, value) }
                    }
                }
            }
        }
    }
}
