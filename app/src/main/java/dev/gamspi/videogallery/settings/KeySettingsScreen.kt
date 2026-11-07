package dev.gamspi.videogallery.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import dev.gamspi.videogallery.torrents.ProviderId
import dev.gamspi.videogallery.ui.icons.AppIcons
import dev.gamspi.videogallery.ui.theme.AppColors

/** «Ключи»: one item per service with a filled/empty status; focus returns to the opened service. */
@Composable
internal fun KeysListSection(viewModel: SettingsViewModel, onOpenService: (KeyService) -> Unit) {
    var lastName by rememberSaveable { mutableStateOf(KeyService.entries.first().name) }
    val requesters = remember { KeyService.entries.associateWith { FocusRequester() } }
    // Bumped on every resume so statuses re-read values saved on a service screen.
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }
    val target = remember { KeyService.entries.firstOrNull { it.name == lastName } ?: KeyService.entries.first() }
    InitialFocus(requesters.getValue(target))
    SectionColumn(SettingsSection.Keys.title) {
        KeyService.entries.forEach { service ->
            val set = remember(refresh) { viewModel.isKeySet(service) }
            ListItem(
                selected = false,
                onClick = {
                    lastName = service.name
                    onOpenService(service)
                },
                headlineContent = { Text(service.title) },
                trailingContent = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (set) "задан" else "пусто",
                            color = LocalContentColor.current.copy(alpha = 0.6f),
                        )
                        Icon(AppIcons.ChevronRight, contentDescription = null)
                    }
                },
                colors = ListItemDefaults.colors(
                    focusedContainerColor = AppColors.Accent,
                    focusedContentColor = AppColors.OnAccent,
                ),
                modifier = Modifier.focusRequester(requesters.getValue(service)),
            )
        }
    }
}

/** Fields of one service; only that service's values are written on Back. */
@Composable
fun KeyServiceScreen(
    service: KeyService,
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    when (service) {
        KeyService.PoiskKino, KeyService.Kinopoisk, KeyService.Tmdb -> CatalogKeyScreen(service, viewModel, onBack)
        KeyService.Rutor, KeyService.Apibay, KeyService.Yts, KeyService.Rutracker ->
            TrackerKeyScreen(service, requireNotNull(service.tracker), viewModel, onBack)
        KeyService.OpenSubtitles -> OpenSubtitlesKeyScreen(viewModel, onBack)
        KeyService.Proxy -> ProxyKeyScreen(viewModel, onBack)
    }
}

/** Shared frame: title, save error, first-field focus and save-on-Back. */
@Composable
private fun KeyServiceFrame(
    service: KeyService,
    onBack: () -> Unit,
    save: () -> Boolean,
    content: @Composable (firstFocus: Modifier) -> Unit,
) {
    var saveError by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }
    InitialFocus(firstFocus)
    // While the IME is open the system closes it first, so this only runs on a real Back.
    BackHandler {
        if (save()) onBack() else saveError = true
    }
    SectionColumn(service.title, saveError) {
        content(Modifier.focusRequester(firstFocus))
    }
}

@Composable
private fun CatalogKeyScreen(service: KeyService, viewModel: SettingsViewModel, onBack: () -> Unit) {
    // Credentials persist only through SecretStore, never Activity saved state.
    var key by remember {
        mutableStateOf(
            when (service) {
                KeyService.Kinopoisk -> viewModel.kinopoiskApiKey.value
                KeyService.Tmdb -> viewModel.tmdbApiKey.value
                else -> viewModel.poiskkinoApiKey.value
            }.orEmpty(),
        )
    }
    val (label, hint) = when (service) {
        KeyService.Kinopoisk -> "Ключ Кинопоиска" to "Получить: kinopoiskapiunofficial.tech → Профиль"
        KeyService.Tmdb -> "Ключ TMDB API" to "Получить: themoviedb.org → Settings → API"
        else -> "Ключ PoiskKino" to "Получить: poiskkino.dev → Telegram-бот"
    }
    KeyServiceFrame(service, onBack, save = { viewModel.saveCatalogKey(service, key) }) { first ->
        TvTextField(value = key, onValueChange = { key = it }, label = label, modifier = first, secret = true)
        Text(hint)
    }
}

@Composable
private fun TrackerKeyScreen(service: KeyService, id: ProviderId, viewModel: SettingsViewModel, onBack: () -> Unit) {
    var url by remember { mutableStateOf(viewModel.tracker(id).value.baseUrl) }
    val isRutracker = id == ProviderId.Rutracker
    var user by remember { mutableStateOf(if (isRutracker) viewModel.rutrackerUser.orEmpty() else "") }
    var password by remember { mutableStateOf(if (isRutracker) viewModel.rutrackerPassword.orEmpty() else "") }
    KeyServiceFrame(
        service,
        onBack,
        save = {
            viewModel.saveTrackerUrls(mapOf(id to url)) &&
                (!isRutracker || viewModel.saveRutracker(user, password))
        },
    ) { first ->
        TvTextField(value = url, onValueChange = { url = it }, label = "Адрес ${id.label}", modifier = first)
        if (isRutracker) {
            TvTextField(value = user, onValueChange = { user = it }, label = "Логин rutracker")
            TvTextField(value = password, onValueChange = { password = it }, label = "Пароль rutracker", secret = true)
        }
    }
}

@Composable
private fun OpenSubtitlesKeyScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    var key by remember { mutableStateOf(viewModel.openSubtitlesKey.orEmpty()) }
    var user by remember { mutableStateOf(viewModel.openSubtitlesUser.orEmpty()) }
    var password by remember { mutableStateOf(viewModel.openSubtitlesPassword.orEmpty()) }
    KeyServiceFrame(KeyService.OpenSubtitles, onBack, save = { viewModel.saveOpenSubtitles(key, user, password) }) { first ->
        TvTextField(value = key, onValueChange = { key = it }, label = "OpenSubtitles ключ", modifier = first, secret = true)
        TvTextField(value = user, onValueChange = { user = it }, label = "OpenSubtitles логин")
        TvTextField(value = password, onValueChange = { password = it }, label = "OpenSubtitles пароль", secret = true)
    }
}

@Composable
private fun ProxyKeyScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    var url by remember { mutableStateOf(viewModel.proxyUrl.value.orEmpty()) }
    var key by remember { mutableStateOf(viewModel.proxyKey.value.orEmpty()) }
    KeyServiceFrame(KeyService.Proxy, onBack, save = { viewModel.saveNetwork(url, key) }) { first ->
        TvTextField(
            value = url,
            onValueChange = { url = it },
            label = "Адрес прокси",
            modifier = first,
            placeholder = "https://имя.workers.dev",
        )
        TvTextField(value = key, onValueChange = { key = it }, label = "Ключ прокси", secret = true)
    }
}
