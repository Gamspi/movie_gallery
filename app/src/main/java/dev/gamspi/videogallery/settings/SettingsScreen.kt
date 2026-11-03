package dev.gamspi.videogallery.settings

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.gamspi.videogallery.torrents.ProviderId
import dev.gamspi.videogallery.ui.components.AccentButton

@Composable
fun SettingsSectionScreen(
    section: SettingsSection,
    onBack: () -> Unit,
    onPlayTest: (String) -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    when (section) {
        SettingsSection.Catalog -> CatalogSection(viewModel, onBack)
        SettingsSection.Trackers -> TrackersSection(viewModel, onBack)
        SettingsSection.Storage -> StorageSection(viewModel)
        SettingsSection.Subtitles -> SubtitlesSection(viewModel)
        SettingsSection.Translation -> TranslationSection(viewModel)
        SettingsSection.OpenSubtitles -> OpenSubtitlesSection(viewModel, onBack)
        SettingsSection.Debug -> DebugSection(viewModel, onPlayTest)
    }
}

private const val SAVE_ERROR = "Не удалось сохранить"

@Composable
private fun SectionColumn(
    title: String,
    saveError: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        if (saveError) Text(SAVE_ERROR, color = Color(0xFFFF8A80))
        content()
    }
}

/** Requests focus on [requester] once when the section opens. */
@Composable
private fun InitialFocus(requester: FocusRequester) {
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
}

@Composable
private fun CatalogSection(viewModel: SettingsViewModel, onBack: () -> Unit) {
    var sourceName by rememberSaveable { mutableStateOf(viewModel.catalogSource.value.name) }
    // Credentials persist only through SecretStore, never Activity saved state.
    var poiskkinoKey by remember { mutableStateOf(viewModel.poiskkinoApiKey.value.orEmpty()) }
    var kinopoiskKey by remember { mutableStateOf(viewModel.kinopoiskApiKey.value.orEmpty()) }
    var tmdbKey by remember { mutableStateOf(viewModel.tmdbApiKey.value.orEmpty()) }
    val source = CatalogSource.entries.firstOrNull { it.name == sourceName } ?: CatalogSource.PoiskKino
    var saveError by remember { mutableStateOf(false) }
    val poiskkinoFocus = remember { FocusRequester() }
    val kinopoiskFocus = remember { FocusRequester() }
    val tmdbFocus = remember { FocusRequester() }
    // Initial focus: the button of the currently selected source.
    val initialSource = remember { source }
    LaunchedEffect(Unit) {
        runCatching {
            when (initialSource) {
                CatalogSource.PoiskKino -> poiskkinoFocus.requestFocus()
                CatalogSource.Kinopoisk -> kinopoiskFocus.requestFocus()
                CatalogSource.Tmdb -> tmdbFocus.requestFocus()
            }
        }
    }
    // While the IME is open the system closes it first, so this only runs on a real Back.
    BackHandler {
        if (viewModel.saveCatalog(source, poiskkinoKey, kinopoiskKey, tmdbKey)) onBack() else saveError = true
    }
    SectionColumn(SettingsSection.Catalog.title, saveError) {
        Text("Источник каталога")
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SourceButton("PoiskKino", source == CatalogSource.PoiskKino, poiskkinoFocus) {
                sourceName = CatalogSource.PoiskKino.name
            }
            SourceButton("Кинопоиск", source == CatalogSource.Kinopoisk, kinopoiskFocus) {
                sourceName = CatalogSource.Kinopoisk.name
            }
            SourceButton("TMDB", source == CatalogSource.Tmdb, tmdbFocus) {
                sourceName = CatalogSource.Tmdb.name
            }
        }
        TvTextField(
            value = poiskkinoKey,
            onValueChange = { poiskkinoKey = it },
            label = "Ключ PoiskKino",
        )
        Text("Получить: poiskkino.dev → Telegram-бот")
        TvTextField(
            value = kinopoiskKey,
            onValueChange = { kinopoiskKey = it },
            label = "Ключ Кинопоиска",
        )
        Text("Получить: kinopoiskapiunofficial.tech → Профиль")
        TvTextField(
            value = tmdbKey,
            onValueChange = { tmdbKey = it },
            label = "Ключ TMDB API",
        )
        Text("Получить: themoviedb.org → Settings → API")
    }
}

@Composable
private fun TrackersSection(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val trackerEnabled = ProviderId.entries.associateWith { id ->
        rememberSaveable(id.name) { mutableStateOf(viewModel.tracker(id).value.enabled) }
    }
    val trackerUrls = ProviderId.entries.associateWith { id ->
        rememberSaveable(id.name + "_url") { mutableStateOf(viewModel.tracker(id).value.baseUrl) }
    }
    var saveError by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }
    InitialFocus(firstFocus)
    BackHandler {
        val trackers = ProviderId.entries.associateWith {
            TrackerConfig(trackerEnabled.getValue(it).value, trackerUrls.getValue(it).value)
        }
        if (viewModel.saveTrackers(trackers)) onBack() else saveError = true
    }
    SectionColumn(SettingsSection.Trackers.title, saveError) {
        ProviderId.entries.forEachIndexed { index, id ->
            val on = trackerEnabled.getValue(id).value
            SourceButton(id.label, on, if (index == 0) firstFocus else null) {
                trackerEnabled.getValue(id).value = !on
            }
            TvTextField(
                value = trackerUrls.getValue(id).value,
                onValueChange = { trackerUrls.getValue(id).value = it },
                label = "Адрес ${id.label}",
            )
        }
    }
}

@Composable
private fun StorageSection(viewModel: SettingsViewModel) {
    val storage by viewModel.torrentStoragePrefs.collectAsState()
    val clearState by viewModel.torrentCacheClear.collectAsState()
    val firstFocus = remember { FocusRequester() }
    InitialFocus(firstFocus)
    SectionColumn(SettingsSection.Storage.title) {
        AccentButton(onClick = viewModel::toggleTorrentRetention, modifier = Modifier.focusRequester(firstFocus)) {
            Text("После просмотра: ${if (storage.keepAfterPlayback) "хранить" else "удалять"}")
        }
        Text("Режим применяется при выходе из просмотра. Хранение не продолжает раздачу.")
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            AccentButton(onClick = { viewModel.changeTorrentLimit(-1) }, enabled = storage.limitGiB > 1) { Text("−1 ГиБ") }
            Text("Лимит: ${storage.limitGiB} ГиБ")
            AccentButton(onClick = { viewModel.changeTorrentLimit(1) }, enabled = storage.limitGiB < 100) { Text("+1 ГиБ") }
        }
        Text("При нехватке места удаляются самые старые сохранённые раздачи. Активная раздача не удаляется.")
        AccentButton(onClick = viewModel::clearTorrentCache, enabled = !clearState.busy) {
            Text(if (clearState.busy) "Очищаем…" else "Очистить сохранённые торренты")
        }
        clearState.message?.let { Text(it) }
    }
}

@Composable
private fun SubtitlesSection(viewModel: SettingsViewModel) {
    val prefs by viewModel.subtitlePrefs.collectAsState()
    val firstFocus = remember { FocusRequester() }
    InitialFocus(firstFocus)
    SectionColumn(SettingsSection.Subtitles.title) {
        SubLayer.entries.forEach { layer ->
            val name = layer.name
            val isA = layer == SubLayer.A
            val lang = if (isA) prefs.languageA else prefs.languageB
            val style = if (isA) prefs.styleA else prefs.styleB
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AccentButton(
                    onClick = { viewModel.cycleLanguage(layer) },
                    modifier = if (isA) Modifier.focusRequester(firstFocus) else Modifier,
                ) { Text("Язык $name: $lang") }
                AccentButton(onClick = { viewModel.cycleSize(layer) }) { Text("Размер $name: ${style.size.label}") }
                AccentButton(onClick = { viewModel.cycleColor(layer) }) { Text("Цвет $name: ${style.color.label}") }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Сдвиг $name")
                AccentButton(onClick = { viewModel.changeOffset(layer, -SUB_OFFSET_STEP_MS) }) { Text("−0,5 с") }
                Text(formatOffset(style.offsetMs))
                AccentButton(onClick = { viewModel.changeOffset(layer, SUB_OFFSET_STEP_MS) }) { Text("+0,5 с") }
            }
            Text(
                "Только для скачанных субтитров (OpenSubtitles)",
                color = Color.Gray,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun TranslationSection(viewModel: SettingsViewModel) {
    val modelStatus by viewModel.modelStatus.collectAsState()
    val modelMessage by viewModel.modelMessage.collectAsState()
    val firstFocus = remember { FocusRequester() }
    InitialFocus(firstFocus)
    SectionColumn(SettingsSection.Translation.title) {
        Text(modelStatus)
        // Buttons stay enabled so focus is never lost; the view model ignores repeated presses.
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AccentButton(onClick = viewModel::downloadModels, modifier = Modifier.focusRequester(firstFocus)) {
                Text("Скачать модели перевода")
            }
            AccentButton(onClick = viewModel::deleteModels) { Text("Удалить модели перевода") }
        }
        modelMessage?.let { Text(it) }
    }
}

@Composable
private fun OpenSubtitlesSection(viewModel: SettingsViewModel, onBack: () -> Unit) {
    var osKey by remember { mutableStateOf(viewModel.openSubtitlesKey.orEmpty()) }
    var osUser by remember { mutableStateOf(viewModel.openSubtitlesUser.orEmpty()) }
    var osPassword by remember { mutableStateOf(viewModel.openSubtitlesPassword.orEmpty()) }
    var saveError by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }
    InitialFocus(firstFocus)
    BackHandler {
        if (viewModel.saveOpenSubtitles(osKey, osUser, osPassword)) onBack() else saveError = true
    }
    SectionColumn(SettingsSection.OpenSubtitles.title, saveError) {
        TvTextField(
            value = osKey,
            onValueChange = { osKey = it },
            label = "OpenSubtitles ключ",
            modifier = Modifier.focusRequester(firstFocus),
        )
        TvTextField(
            value = osUser,
            onValueChange = { osUser = it },
            label = "OpenSubtitles логин",
        )
        TvTextField(
            value = osPassword,
            onValueChange = { osPassword = it },
            label = "OpenSubtitles пароль",
            visualTransformation = PasswordVisualTransformation(),
        )
    }
}

@Composable
private fun DebugSection(viewModel: SettingsViewModel, onPlayTest: (String) -> Unit) {
    var testUrl by rememberSaveable { mutableStateOf(viewModel.testUrl.value.orEmpty()) }
    var returnToPlay by rememberSaveable { mutableStateOf(false) }
    val urlFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    // «Играть» when returning from the player, else the URL field.
    LaunchedEffect(Unit) { runCatching { (if (returnToPlay) playFocus else urlFocus).requestFocus() } }
    SectionColumn(SettingsSection.Debug.title) {
        TvTextField(
            value = testUrl,
            onValueChange = { testUrl = it },
            label = "Тестовый URL",
            modifier = Modifier.focusRequester(urlFocus),
        )
        AccentButton(
            onClick = {
                returnToPlay = true
                viewModel.setTestUrl(testUrl)
                onPlayTest(testUrl.trim())
            },
            enabled = testUrl.isNotBlank(),
            modifier = Modifier.focusRequester(playFocus),
        ) { Text("Играть") }
    }
}

private fun formatOffset(ms: Long): String {
    val sign = if (ms > 0) "+" else if (ms < 0) "−" else ""
    val abs = kotlin.math.abs(ms)
    return "$sign${abs / 1000},${(abs % 1000) / 100} с"
}

/** Text field safe for D-pad: read-only until OK is pressed, so Up/Down never get trapped. */
@Composable
private fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var editing by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = visualTransformation,
        readOnly = !editing,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            editing = false
            focusManager.moveFocus(FocusDirection.Down)
        }),
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { if (!it.hasFocus) editing = false }
            .onPreviewKeyEvent { ev ->
                if (editing) return@onPreviewKeyEvent false
                val code = ev.nativeKeyEvent.keyCode
                val isDown = ev.type == KeyEventType.KeyDown
                when (code) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (isDown) {
                            focusManager.moveFocus(
                                if (code == KeyEvent.KEYCODE_DPAD_UP) FocusDirection.Up else FocusDirection.Down,
                            )
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (isDown) {
                            editing = true
                            keyboard?.show()
                        }
                        true
                    }
                    else -> false
                }
            },
    )
}

@Composable
private fun SourceButton(
    label: String,
    selected: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    AccentButton(
        onClick = onClick,
        selected = selected,
        modifier = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier,
    ) { Text(if (selected) "✓ $label" else label) }
}
