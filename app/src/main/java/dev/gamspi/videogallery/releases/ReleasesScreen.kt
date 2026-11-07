package dev.gamspi.videogallery.releases

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.focus.focusProperties
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import dev.gamspi.videogallery.ui.theme.AppColors
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gamspi.videogallery.history.PlaybackMeta
import dev.gamspi.videogallery.ui.components.AccentButton
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.gamspi.videogallery.torrents.Quality
import dev.gamspi.videogallery.torrents.TorrentResult
import dev.gamspi.videogallery.torrents.ProviderId
import dev.gamspi.videogallery.torrents.UnavailableReason
import dev.gamspi.videogallery.torrents.formatSize
import dev.gamspi.videogallery.torrents.key
import dev.gamspi.videogallery.torrents.needsResolve
import dev.gamspi.videogallery.ui.components.CatMascot
import dev.gamspi.videogallery.ui.components.CatPose
import dev.gamspi.videogallery.ui.components.ErrorView
import dev.gamspi.videogallery.ui.components.FullScreenLoader

fun releaseInfoLine(r: TorrentResult): String =
    listOfNotNull(
        r.quality?.label,
        formatSize(r.sizeBytes),
        "↑${r.seeders} ↓${r.leechers}",
        r.provider.label,
    ).joinToString(" · ")

/** Per-provider explanation for a provider that gave no results. */
fun unavailableNote(id: ProviderId, reason: UnavailableReason?): String = when (reason) {
    UnavailableReason.Captcha -> "${id.label} просит капчу — войди один раз в браузере"
    UnavailableReason.BadCredentials -> "${id.label}: неверный логин или пароль"
    UnavailableReason.NoCredentials -> "${id.label}: укажи логин и пароль в настройках"
    UnavailableReason.Unavailable, null -> "${id.label} недоступен"
}

@Composable
fun ReleasesScreen(
    onOpenKeySettings: () -> Unit,
    onOpenSettings: () -> Unit,
    onPlay: (TorrentResult, PlaybackMeta) -> Unit,
    viewModel: ReleasesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val play by viewModel.play.collectAsStateWithLifecycle()
    val missingNote by viewModel.missingNote.collectAsStateWithLifecycle()
    LaunchedEffect(play) {
        val request = play ?: return@LaunchedEffect
        viewModel.onPlayHandled()
        onPlay(request.release, request.meta)
    }
    when (val s = state) {
        ReleasesUiState.Loading -> FullScreenLoader("Ищем раздачи…")
        is ReleasesUiState.Error -> ErrorView(s.kind, onRetry = viewModel::retry, onOpenKeySettings = onOpenKeySettings, onOpenSettings = onOpenSettings)
        is ReleasesUiState.Content -> ReleasesContent(
            s,
            missingNote,
            FilterActions(
                viewModel::setQuality,
                viewModel::toggleProvider,
                viewModel::toggleDead,
                viewModel::toggleOverLimit,
            ),
        ) {
            // Releases without a magnet are resolved first; the rest open directly.
            if (it.needsResolve) viewModel.resolve(it) else onPlay(it, s.metaFor(it))
        }
    }
}

private class FilterActions(
    val onQuality: (Quality?) -> Unit,
    val onProvider: (ProviderId) -> Unit,
    val onDead: () -> Unit,
    val onOverLimit: () -> Unit,
)

@Composable
private fun ReleasesContent(
    content: ReleasesUiState.Content,
    missingNote: String?,
    actions: FilterActions,
    onPlay: (TorrentResult) -> Unit,
) {
    val filterFocus = remember { FocusRequester() }
    val restoreFocus = remember { FocusRequester() }
    val qualityFocus = remember { (listOf<Quality?>(null) + Quality.entries).associateWith { FocusRequester() } }
    val qualityRowFocus = remember { FocusRequester() }
    var qualityView by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val visible = content.visible
    val filter = content.filter
    var panelOpen by rememberSaveable { mutableStateOf(false) }
    // Survive navigation: the last focused release.
    var focusedKey by rememberSaveable { mutableStateOf("") }
    val restoreIndex = visible.indexOfFirst { it.key == focusedKey }.coerceAtLeast(0)
    // Initial focus only: last focused row, else first row, otherwise the «Фильтр» button.
    LaunchedEffect(Unit) {
        if (visible.isNotEmpty()) listState.scrollToItem(restoreIndex)
        withFrameNanos { }
        if (!panelOpen) {
            runCatching { if (visible.isNotEmpty()) restoreFocus.requestFocus() else filterFocus.requestFocus() }
        }
    }
    // Opening focuses the selected quality; closing returns focus to the button.
    var wasOpen by remember { mutableStateOf(panelOpen) }
    LaunchedEffect(panelOpen, qualityView) {
        withFrameNanos { }
        if (panelOpen) {
            runCatching {
                if (qualityView) qualityFocus.getValue(filter.quality).requestFocus() else qualityRowFocus.requestFocus()
            }
        } else if (wasOpen) {
            qualityView = false
            runCatching { filterFocus.requestFocus() }
        }
        wasOpen = panelOpen
    }
    BackHandler(enabled = panelOpen) { if (qualityView) qualityView = false else panelOpen = false }
    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(48.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(content.movieTitle, style = MaterialTheme.typography.headlineMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                AccentButton(
                    onClick = { panelOpen = true },
                    modifier = Modifier.focusProperties { canFocus = !panelOpen }.focusRequester(filterFocus),
                ) { Text("Фильтр") }
                Text(
                    filterSummary(content.all, visible.size, filter),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (content.unavailable.isNotEmpty()) {
                Text(
                    content.unavailable.joinToString(", ") { unavailableNote(it, content.reasons[it]) },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            missingNote?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = AppColors.Accent)
            }
            content.message?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFFF8A80))
            }
            if (visible.isEmpty()) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CatMascot(
                        CatPose.Shrug,
                        size = 120.dp,
                        message = when {
                            content.all.isNotEmpty() -> "Все раздачи скрыты фильтром"
                            content.noProviders -> "Включите трекеры в настройках"
                            else -> "Раздачи не найдены"
                        },
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(visible, key = { _, r -> r.key }) { index, r ->
                        val resolving = content.resolving == r.key
                        ListItem(
                            selected = false,
                            onClick = { onPlay(r) },
                            headlineContent = { Text(r.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(if (resolving) "Открываем раздачу…" else releaseInfoLine(r))
                            },
                            modifier = Modifier.fillMaxWidth()
                                // The list must not take focus while the panel is open.
                                .focusProperties { canFocus = !panelOpen }
                                .then(if (index == restoreIndex) Modifier.focusRequester(restoreFocus) else Modifier)
                                .onFocusChanged { if (it.hasFocus) focusedKey = r.key },
                        )
                    }
                }
            }
        }
        if (panelOpen) {
            FilterPanel(
                content, actions, qualityFocus, qualityRowFocus, qualityView, { qualityView = it },
                Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

/** Right-side panel styled like the player's settings menu; Back belongs to the screen. */
@Composable
private fun FilterPanel(
    content: ReleasesUiState.Content,
    actions: FilterActions,
    qualityFocus: Map<Quality?, FocusRequester>,
    qualityRowFocus: FocusRequester,
    qualityView: Boolean,
    setQualityView: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val filter = content.filter
    Column(
        modifier.fillMaxHeight().width(420.dp).background(Color(0xE6101018)).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(if (qualityView) "Качество" else "Фильтр", color = Color.White)
        if (qualityView) {
            val options = listOf<Quality?>(null) + Quality.entries
            options.forEach { q ->
                PanelItem(q?.label ?: "Все", filter.quality == q, Modifier.focusRequester(qualityFocus.getValue(q))) {
                    actions.onQuality(q)
                    setQualityView(false)
                }
            }
            return@Column
        }
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { setQualityView(true) },
            modifier = Modifier.fillMaxWidth().focusRequester(qualityRowFocus),
            colors = ButtonDefaults.colors(
                containerColor = Color(0xFF34343C),
                contentColor = Color.White,
                focusedContainerColor = AppColors.Accent,
                focusedContentColor = AppColors.OnAccent,
            ),
        ) { Text("Качество: ${filter.quality?.label ?: "Все"} ›") }
        val counts = content.all.providerCounts(filter, content.limitBytes)
        if (counts.isNotEmpty()) {
            Text("Источники", color = Color.LightGray)
            counts.forEach { (id, n) ->
                PanelItem("${id.label} ($n)", id !in filter.hiddenProviders, Modifier) { actions.onProvider(id) }
            }
        }
        Text("Показать", color = Color.LightGray)
        PanelItem("Мёртвые (${content.all.deadCount(filter)})", filter.showDead, Modifier) { actions.onDead() }
        PanelItem(
            "Больше лимита (${content.all.overLimitCount(filter, content.limitBytes)})",
            filter.showOverLimit,
            Modifier,
        ) { actions.onOverLimit() }
        }
    }
}

@Composable
private fun PanelItem(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = ButtonDefaults.colors(
            containerColor = Color(0xFF34343C),
            contentColor = if (selected) AppColors.Accent else Color.White,
            focusedContainerColor = AppColors.Accent,
            focusedContentColor = AppColors.OnAccent,
        ),
    ) { Text(if (selected) "✓ $label" else label) }
}
