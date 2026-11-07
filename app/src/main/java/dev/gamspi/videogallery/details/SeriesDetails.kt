package dev.gamspi.videogallery.details

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.gamspi.videogallery.catalog.model.Episode
import dev.gamspi.videogallery.history.FINISHED_PROGRESS
import dev.gamspi.videogallery.stream.EpisodeRef
import dev.gamspi.videogallery.ui.components.AccentButton
import dev.gamspi.videogallery.ui.components.LocalCatalogSource
import dev.gamspi.videogallery.ui.components.cardProgressFill
import dev.gamspi.videogallery.ui.components.catalogErrorMessage
import dev.gamspi.videogallery.ui.components.formatRating
import dev.gamspi.videogallery.ui.theme.AppColors

private val EpisodeShape = RoundedCornerShape(8.dp)
private val EpisodeFocusBorder = Border(BorderStroke(3.dp, AppColors.Accent), shape = EpisodeShape)
private val EpisodeWidth = 200.dp

internal fun episodeCode(ref: EpisodeRef): String = "S%02dE%02d".format(ref.season, ref.episode)

internal fun seasonsLabel(n: Int): String {
    val word = when {
        n % 100 in 11..14 -> "сезонов"
        n % 10 == 1 -> "сезон"
        n % 10 in 2..4 -> "сезона"
        else -> "сезонов"
    }
    return "$n $word"
}

/** Index of the first not finished episode, else 0. */
internal fun firstUnwatched(episodes: List<Episode>, progress: Map<EpisodeRef, Float>): Int =
    episodes.indexOfFirst { (progress[EpisodeRef(it.season, it.number)] ?: 0f) < FINISHED_PROGRESS }
        .let { if (it < 0) 0 else it }

@Composable
internal fun SeriesDetailsContent(
    c: DetailsUiState.Content,
    onSelectSeason: (Int) -> Unit,
    onRetryEpisodes: () -> Unit,
    onPlay: (EpisodeRef) -> Unit,
    onPickSeason: (Int) -> Unit,
) {
    val details = c.details
    val movie = details.movie
    val watchFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    val requesters = remember(c.selectedSeason, c.episodes) { c.episodes.associate { it.number to FocusRequester() } }
    val firstTarget = c.episodes.getOrNull(firstUnwatched(c.episodes, c.progress))?.let { requesters[it.number] }
    // Last started episode (season * 1000 + number); survives the trip to the player.
    var last by rememberSaveable { mutableIntStateOf(0) }
    // 0 until initial focus is placed; saved so a return from the player does not repeat it.
    var focusPlaced by rememberSaveable { mutableIntStateOf(0) }
    fun play(ref: EpisodeRef) {
        last = ref.season * 1000 + ref.episode
        onPlay(ref)
    }

    LaunchedEffect(Unit) {
        val l = last
        if (l > 0) onSelectSeason(l / 1000)
    }
    LaunchedEffect(c.episodes, c.selectedSeason) {
        val l = last
        val target = if (l > 0 && l / 1000 == c.selectedSeason) requesters[l % 1000] else null
        if (focusPlaced == 1 && l == 0) return@LaunchedEffect
        withFrameNanos { }
        if (target != null) {
            // Back from the player: focus the episode that was played, once.
            last = 0
            focusPlaced = 1
            runCatching { target.requestFocus() }
        } else if (l == 0 && focusPlaced == 0) {
            focusPlaced = 1
            runCatching { watchFocus.requestFocus() }
        }
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
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.75f)))
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.fillMaxWidth()) {
                val poster = Modifier.width(130.dp).aspectRatio(2f / 3f)
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
                    ) { Text(movie.title, style = MaterialTheme.typography.titleMedium) }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        movie.title,
                        style = MaterialTheme.typography.headlineMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val meta = listOfNotNull(
                        movie.year?.toString(),
                        formatRating(movie.rating)?.let { "★ $it" },
                        seasonsLabel(c.seasons.size),
                        details.genres.takeIf { it.isNotEmpty() }?.joinToString(", "),
                    )
                    Text(
                        meta.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (movie.overview.isNotBlank()) {
                        Text(
                            movie.overview,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
                        val resume = c.resume
                        if (resume != null) {
                            AccentButton(
                                onClick = { play(resume) },
                                modifier = Modifier.focusRequester(watchFocus),
                            ) {
                                Text((if (c.started) "▶ Продолжить " else "▶ Смотреть ") + episodeCode(resume))
                            }
                        }
                        AccentButton(onClick = { onPickSeason(c.selectedSeason) }) { Text("Раздачи сезона") }
                    }
                }
            }
            val tabsDown = if (c.episodesError != null) retryFocus else firstTarget
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                c.seasons.forEach { info ->
                    AccentButton(
                        onClick = { onSelectSeason(info.number) },
                        selected = info.number == c.selectedSeason,
                        modifier = Modifier
                            .onFocusChanged { if (it.hasFocus) onSelectSeason(info.number) }
                            .focusProperties { tabsDown?.let { down = it } },
                    ) { Text("Сезон ${info.number}") }
                }
            }
            val error = c.episodesError
            when {
                error != null -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.height(130.dp),
                ) {
                    Text(catalogErrorMessage(error, LocalCatalogSource.current), style = MaterialTheme.typography.bodyLarge)
                    AccentButton(onClick = onRetryEpisodes, modifier = Modifier.focusRequester(retryFocus)) {
                        Text("Повторить")
                    }
                }
                c.episodesLoading -> Box(Modifier.height(130.dp), contentAlignment = Alignment.CenterStart) {
                    Text("Загрузка серий…", style = MaterialTheme.typography.bodyLarge)
                }
                else -> Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
                ) {
                    c.episodes.forEach { ep ->
                        val ref = EpisodeRef(ep.season, ep.number)
                        EpisodeCard(
                            ep,
                            c.progress[ref],
                            onClick = { play(ref) },
                            modifier = requesters[ep.number]?.let { Modifier.focusRequester(it) } ?: Modifier,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeCard(ep: Episode, progress: Float?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(Modifier.width(EpisodeWidth)) {
        Card(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = CardDefaults.shape(EpisodeShape),
            scale = CardDefaults.scale(focusedScale = 1.05f),
            border = CardDefaults.border(focusedBorder = EpisodeFocusBorder, pressedBorder = EpisodeFocusBorder),
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                if (ep.stillUrl != null) {
                    AsyncImage(
                        model = ep.stillUrl,
                        contentDescription = ep.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(
                        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) { Text(ep.number.toString(), style = MaterialTheme.typography.headlineLarge) }
                }
                cardProgressFill(progress)?.let { share ->
                    Box(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp)
                            .background(Color(0x99000000))
                            .drawBehind { drawRect(AppColors.Accent, size = Size(size.width * share, size.height)) },
                    )
                }
            }
        }
        Text(
            "${ep.number}. ${ep.title}",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            formatRuntime(ep.runtimeMin).orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.height(16.dp),
        )
    }
}
