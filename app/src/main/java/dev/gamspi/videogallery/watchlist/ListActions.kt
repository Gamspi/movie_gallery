package dev.gamspi.videogallery.watchlist

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.gamspi.videogallery.ui.components.AccentButton
import dev.gamspi.videogallery.catalog.model.Movie
import dev.gamspi.videogallery.history.WatchEntry
import kotlin.math.roundToInt
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

fun isSeriesRecord(record: ListRecord): Boolean = record.text("type") in setOf("tv_show", "animated_series")

/** Valid per-season scores; damaged entries from other clients are ignored. */
fun seasonRatings(data: Map<String, JsonElement>): Map<String, Int> =
    (data["season_ratings"] as? JsonObject).orEmpty().mapNotNull { (season, value) ->
        val rating = (value as? JsonPrimitive)?.intOrNull
        if (season.toIntOrNull() in 0..9999 && rating != null && rating in 1..10) season to rating else null
    }.toMap()

/** Adds a season score and sets my_rating to the rounded average; a new title becomes watching. */
fun withSeasonRating(data: Map<String, JsonElement>, season: Int, rating: Int, newTitle: Boolean): JsonObject {
    val ratings = seasonRatings(data) + (season.toString() to rating)
    val next = data.toMutableMap()
    next["season_ratings"] = JsonObject(ratings.mapValues { JsonPrimitive(it.value) })
    next["my_rating"] = JsonPrimitive(ratings.values.average().roundToInt())
    if (newTitle) next["status"] = JsonPrimitive("watching")
    return JsonObject(next)
}

fun ratingMissing(record: ListRecord?, season: Int?): Boolean = when {
    record == null -> true
    season != null -> season.toString() !in seasonRatings(record.data)
    else -> record.text("my_rating").isBlank()
}

/** Season scored from places without a season tab: last played, else last marked, else the first. */
fun listDefaultSeason(snapshot: ListSnapshot, titleKey: String): Int {
    val rows = snapshot.records.values.filter { it.titleKey == titleKey && !it.deleted }
    return rows.filter { it.table == ListTable.Positions && it.season > 0 }.maxByOrNull { it.updatedAt }?.season
        ?: rows.filter { it.table == ListTable.Episodes && it.watched }.maxOfOrNull { it.season }
        ?: 1
}

fun findByCatalog(snapshot: ListSnapshot, source: String, id: Int, kind: dev.gamspi.videogallery.catalog.model.MediaKind? = null): ListRecord? = snapshot.records.values.firstOrNull {
    it.table == ListTable.Titles && !it.deleted && it.text("catalog_source") == source && it.text("catalog_id") == id.toString()
        && (kind == null || isSeriesRecord(it) == (kind == dev.gamspi.videogallery.catalog.model.MediaKind.Series))
}

fun titleSeed(movie: Movie, imdb: String, source: String): ListRecord? = importWatchEntry(WatchEntry(
    movieId = movie.id, title = movie.title, year = movie.year, posterUrl = movie.posterUrl, imdb = imdb,
    positionKey = "list", positionMs = 0, durationMs = 0, updatedAt = System.currentTimeMillis(),
    kind = movie.kind, source = source,
)).firstOrNull()?.let(::listCardSeed)?.let { withPremiere(it, movie) }

/** Refresh only the known premiere; user fields and an existing date survive sparse responses. */
fun withPremiere(record: ListRecord, movie: Movie): ListRecord =
    dev.gamspi.videogallery.catalog.model.releaseDate(movie.premiereDate)?.let {
        record.copy(data = JsonObject(record.data + ("premiere_date" to JsonPrimitive(it.toString()))))
    } ?: record

internal fun stepRating(current: Int, delta: Int): Int = (current + delta).coerceIn(1, 10)

private fun isOkKey(event: KeyEvent): Boolean = event.nativeKeyEvent.keyCode in setOf(
    android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)

/** A dialog opened by a long press still receives that press; ignore OK until a fresh one starts. */
@Composable
private fun rememberHeldOkGuard(): Modifier {
    var armed by remember { mutableStateOf(false) }
    return Modifier.onPreviewKeyEvent { event ->
        if (!isOkKey(event)) return@onPreviewKeyEvent false
        if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) armed = true
        if (armed) return@onPreviewKeyEvent false
        if (event.type == KeyEventType.KeyUp) armed = true
        true
    }
}

@Composable
fun StarRatingDialog(title: String, initial: Int?, onRate: (Int) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableIntStateOf(initial ?: 7) }
    var armed by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.width(520.dp).background(MaterialTheme.colorScheme.surface).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            LaunchedEffect(Unit) { focus.requestFocus() }
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text("★".repeat(value) + "☆".repeat(10 - value) + "  $value / 10", style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.focusRequester(focus).onPreviewKeyEvent { event ->
                    val down = event.type == KeyEventType.KeyDown
                    when {
                        isOkKey(event) -> {
                            if (down && event.nativeKeyEvent.repeatCount == 0) armed = true
                            if (!down && armed) onRate(value)
                            true
                        }
                        event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT -> { if (down) value = stepRating(value, -1); true }
                        event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> { if (down) value = stepRating(value, 1); true }
                        else -> false
                    }
                }.focusable())
            Text("Влево/вправо — выбор, OK — принять, Назад — без оценки", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun EpisodeMarkDialog(title: String, onMarkEpisode: () -> Unit, onMarkSeason: () -> Unit, onClear: () -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    val guard = rememberHeldOkGuard()
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.width(440.dp).background(MaterialTheme.colorScheme.surface).padding(24.dp).then(guard),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LaunchedEffect(Unit) { first.requestFocus() }
            Text(title, style = MaterialTheme.typography.titleLarge)
            AccentButton(onClick = onMarkEpisode, modifier = Modifier.focusRequester(first)) { Text("Отметить серию") }
            AccentButton(onClick = onMarkSeason) { Text("Отметить сезон целиком") }
            AccentButton(onClick = onClear) { Text("Снять галочку") }
        }
    }
}

private fun ratingInitial(record: ListRecord, season: Int?): Int? =
    if (season != null) seasonRatings(record.data)[season.toString()] else record.text("my_rating").toIntOrNull()

@Composable
fun RateButton(record: ListRecord, season: Int?, rateSeason: (Int, Int) -> Unit, change: (String, JsonElement) -> Unit) {
    var opened by remember { mutableStateOf(false) }
    var restore by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(opened, restore) { if (!opened && restore) { focus.requestFocus(); restore = false } }
    val target = season.takeIf { isSeriesRecord(record) }
    AccentButton(onClick = { opened = true }, modifier = Modifier.focusRequester(focus)) { Text(if (target != null) "Оценить сезон $target" else "Оценить") }
    if (opened) StarRatingDialog(if (target != null) "Сезон $target" else "Оценка", ratingInitial(record, target),
        onRate = { value ->
            if (target != null) rateSeason(target, value) else change("my_rating", JsonPrimitive(value))
            opened = false; restore = true
        }, onDismiss = { opened = false; restore = true })
}

@Composable
fun ListActionButton(
    record: ListRecord,
    modifier: Modifier = Modifier,
    onDialog: (Boolean) -> Unit = {},
    ratingSeason: Int? = null,
    rateSeason: (Int, Int) -> Unit = { _, _ -> },
    change: (String, JsonElement) -> Unit,
) {
    var opened by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    var restore by remember { mutableStateOf(false) }
    LaunchedEffect(opened, restore) { if (!opened && restore) { focus.requestFocus(); restore = false } }
    AccentButton(onClick = { onDialog(true); opened = true }, modifier = modifier.focusRequester(focus)) { Text("Личный список") }
    if (opened) ListActionsDialog(record, { opened = false; restore = true; onDialog(false) }, change, rateSeason, ratingSeason)
}

@Composable
fun ListActionsDialog(
    record: ListRecord,
    dismiss: () -> Unit,
    change: (String, JsonElement) -> Unit,
    rateSeason: (Int, Int) -> Unit = { _, _ -> },
    ratingSeason: Int? = null,
) {
    var rating by remember { mutableStateOf(false) }
    if (rating) {
        val season = if (isSeriesRecord(record)) ratingSeason ?: 1 else null
        StarRatingDialog(if (season != null) "Сезон $season" else "Оценка", ratingInitial(record, season),
            onRate = { value ->
                if (season != null) rateSeason(season, value) else change("my_rating", JsonPrimitive(value))
                dismiss()
            }, onDismiss = { rating = false })
        return
    }
    val first = remember { FocusRequester() }
    val guard = rememberHeldOkGuard()
    Dialog(onDismissRequest = dismiss) {
        Column(Modifier.width(440.dp).background(MaterialTheme.colorScheme.surface).padding(24.dp)
            .verticalScroll(rememberScrollState()).then(guard), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(record.text("title_ru").ifBlank { record.text("title_en") }, style = MaterialTheme.typography.titleLarge)
            LaunchedEffect(Unit) { first.requestFocus() }
            listOf("К просмотру" to "unwatched", "Смотрю" to "watching", "Смотрел" to "watched",
                "Бросил" to "dropped", "Не предлагать" to "dismissed").forEachIndexed { i, (label, status) ->
                AccentButton(onClick = { change("status", JsonPrimitive(status)); dismiss() },
                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier) { Text(label) }
            }
            AccentButton(onClick = { rating = true }) { Text("Оценить") }
            if (isSeriesRecord(record)) {
                val on = record.text("notify") == "true"
                AccentButton(onClick = { change("notify", JsonPrimitive(!on)); dismiss() }) { Text(if (on) "Не уведомлять" else "Уведомлять") }
            }
            AccentButton(onClick = { change("deleted", JsonPrimitive(true)); dismiss() }) { Text("Убрать из списка") }
            AccentButton(onClick = dismiss) { Text("Закрыть") }
        }
    }
}
