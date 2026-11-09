package dev.gamspi.videogallery.watchlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.gamspi.videogallery.ui.components.AccentButton
import kotlinx.serialization.json.*

fun ListRecord.text(field: String): String = (data[field] as? JsonPrimitive)?.contentOrNull.orEmpty()

fun listTitles(snapshot: ListSnapshot, status: String): List<ListRecord> = snapshot.records.values
    .filter { it.table == ListTable.Titles && !it.deleted && it.text("status") == status }
    .sortedByDescending { it.updatedAt }

/** Card seed for a title that is not in the list yet: never started, no notifications. */
fun listCardSeed(seed: ListRecord): ListRecord = seed.copy(data = JsonObject(seed.data +
    mapOf("status" to JsonPrimitive("unwatched"), "notify" to JsonPrimitive(false))))

/** First edit of an unlisted title: a rating means it was watched, for series too. */
fun firstEditSeed(seed: ListRecord, field: String): ListRecord =
    if (field != "my_rating") seed else seed.copy(data = JsonObject(seed.data + ("status" to JsonPrimitive("watched"))))

@Composable
fun ListActionButton(record: ListRecord, modifier: Modifier = Modifier, onDialog: (Boolean) -> Unit = {}, change: (String, JsonElement) -> Unit) {
    var opened by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    var restore by remember { mutableStateOf(false) }
    LaunchedEffect(opened, restore) { if (!opened && restore) { focus.requestFocus(); restore = false } }
    AccentButton(onClick = { onDialog(true); opened = true }, modifier = modifier.focusRequester(focus)) { Text("Личный список") }
    if (opened) ListActionsDialog(record, { opened = false; restore = true; onDialog(false) }, change)
}

@Composable
fun ListActionsDialog(record: ListRecord, dismiss: () -> Unit, change: (String, JsonElement) -> Unit) {
    var rating by remember { mutableStateOf(false) }
    val first = remember(rating) { FocusRequester() }
    Dialog(onDismissRequest = dismiss) {
        Column(Modifier.width(440.dp).background(MaterialTheme.colorScheme.surface).padding(24.dp)
            .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(record.text("title_ru").ifBlank { record.text("title_en") }, style = MaterialTheme.typography.titleLarge)
            LaunchedEffect(rating) { first.requestFocus() }
            if (rating) {
                (1..10).forEach { n ->
                    AccentButton(onClick = { change("my_rating", JsonPrimitive(n)); dismiss() },
                        modifier = if (n == 1) Modifier.focusRequester(first) else Modifier) { Text("$n / 10") }
                }
            } else {
                listOf("К просмотру" to "unwatched", "Смотрю" to "watching", "Смотрел" to "watched",
                    "Бросил" to "dropped", "Не предлагать" to "dismissed").forEachIndexed { i, (label, status) ->
                    AccentButton(onClick = { change("status", JsonPrimitive(status)); dismiss() },
                        modifier = if (i == 0) Modifier.focusRequester(first) else Modifier) { Text(label) }
                }
                AccentButton(onClick = { rating = true }) { Text("Оценить") }
                if (record.text("type") in listOf("tv_show", "animated_series")) {
                    val on = record.text("notify") == "true"
                    AccentButton(onClick = { change("notify", JsonPrimitive(!on)); dismiss() }) { Text(if(on) "Не уведомлять" else "Уведомлять") }
                }
                AccentButton(onClick = { change("deleted", JsonPrimitive(true)); dismiss() }) { Text("Убрать из списка") }
            }
            AccentButton(onClick = dismiss) { Text("Закрыть") }
        }
    }
}
