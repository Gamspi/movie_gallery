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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.gamspi.videogallery.torrents.ProviderId
import dev.gamspi.videogallery.ui.components.AccentButton
import kotlinx.coroutines.delay

@Composable
fun SettingsSectionScreen(
    section: SettingsSection,
    onBack: () -> Unit,
    onPlayTest: (String) -> Unit = {},
    onOpenKeyService: (KeyService) -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    when (section) {
        SettingsSection.Catalog -> CatalogSection(viewModel, onBack)
        SettingsSection.Trackers -> TrackersSection(viewModel, onBack)
        SettingsSection.Storage -> StorageSection(viewModel)
        SettingsSection.Subtitles -> SubtitlesSection(viewModel)
        SettingsSection.Translation -> TranslationSection(viewModel)
        SettingsSection.Network -> NetworkSection(viewModel)
        SettingsSection.Keys -> KeysListSection(viewModel, onOpenKeyService)
        SettingsSection.Debug -> DebugSection(viewModel, onPlayTest)
    }
}

internal const val SAVE_ERROR = "Не удалось сохранить"

@Composable
internal fun SectionColumn(
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
internal fun InitialFocus(requester: FocusRequester) {
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
}

@Composable
private fun CatalogSection(viewModel: SettingsViewModel, onBack: () -> Unit) {
    var sourceName by rememberSaveable { mutableStateOf(viewModel.catalogSource.value.name) }
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
    BackHandler {
        if (viewModel.saveCatalogSource(source)) onBack() else saveError = true
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
        Text("Ключи источников — в разделе «Ключи»", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TrackersSection(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val trackerEnabled = ProviderId.entries.associateWith { id ->
        rememberSaveable(id.name) { mutableStateOf(viewModel.tracker(id).value.enabled) }
    }
    var saveError by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }
    InitialFocus(firstFocus)
    BackHandler {
        val enabled = ProviderId.entries.associateWith { trackerEnabled.getValue(it).value }
        if (viewModel.saveTrackerEnabled(enabled)) onBack() else saveError = true
    }
    SectionColumn(SettingsSection.Trackers.title, saveError) {
        ProviderId.entries.forEachIndexed { index, id ->
            val on = trackerEnabled.getValue(id).value
            SourceButton(id.label, on, if (index == 0) firstFocus else null) {
                trackerEnabled.getValue(id).value = !on
            }
        }
        val rememberFilter by viewModel.rememberReleaseFilter.collectAsState()
        SourceButton("Запоминать фильтр раздач", rememberFilter, null) { viewModel.toggleRememberReleaseFilter() }
        Text("Адреса трекеров и вход rutracker — в разделе «Ключи»", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
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
                AccentButton(onClick = { viewModel.cycleColor(layer) }) { Text("Цвет $name: ${style.color.label}") }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Размер $name")
                // Stays enabled at the limits so focus is never lost; the step clamps.
                AccentButton(onClick = { viewModel.changeScale(layer, -1) }) { Text("−10 %") }
                Text(formatSubScale(style.scalePercent))
                AccentButton(onClick = { viewModel.changeScale(layer, 1) }) { Text("+10 %") }
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
private fun NetworkSection(viewModel: SettingsViewModel) {
    val enabled by viewModel.proxyEnabled.collectAsState()
    val listEnabled by viewModel.listSyncEnabled.collectAsState()
    val listStatus by viewModel.listSyncStatus.collectAsState()
    val listError by viewModel.listSyncError.collectAsState()
    val listQueue by viewModel.listQueue.collectAsState()
    var saveError by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }
    InitialFocus(firstFocus)
    SectionColumn(SettingsSection.Network.title, saveError) {
        SourceButton("Прокси через Cloudflare", enabled, firstFocus) {
            if (!viewModel.toggleProxy()) saveError = true
        }
        Text("Через прокси идут TMDB, постеры TMDB, rutor и rutracker. Торренты — напрямую.")
        Text("Адрес и ключ прокси — в разделе «Ключи»", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        SourceButton("Синхронизация личного списка", listEnabled, null) { viewModel.toggleListSync() }
        Text(when (val status = listStatus) {
            dev.gamspi.videogallery.watchlist.ListSyncStatus.Disabled -> "Синхронизация выключена"
            dev.gamspi.videogallery.watchlist.ListSyncStatus.Idle -> "Список синхронизирован"
            dev.gamspi.videogallery.watchlist.ListSyncStatus.Running -> "Синхронизация…"
            is dev.gamspi.videogallery.watchlist.ListSyncStatus.Unavailable -> "Сервер недоступен с " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(status.since))
        })
        Text("В очереди: $listQueue")
        listError?.let { Text(it) }
        if (listEnabled) AccentButton(onClick = viewModel::syncListNow) { Text("Синхронизировать сейчас") }
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

/**
 * Text field safe for D-pad: read-only until OK is pressed, so Up/Down never get trapped.
 * A [secret] value is masked unless being edited.
 */
@Composable
internal fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    secret: Boolean = false,
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    var editing by remember { mutableStateOf(false) }
    // Back up of a Back press that ended editing; swallowed so the screen stays open.
    var swallowBackUp by remember { mutableStateOf(false) }
    // Back closes the IME without onDone. Compose IME insets need an edge-to-edge window,
    // so read the root insets directly while editing.
    LaunchedEffect(editing) {
        if (!editing) return@LaunchedEffect
        val tracker = ImeDismissTracker()
        while (true) {
            val visible = ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            if (tracker.onVisibility(visible)) {
                editing = false
                break
            }
            delay(IME_POLL_MS)
        }
    }
    fun endEdit() {
        editing = false
        keyboard?.hide()
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        visualTransformation = if (secret && !editing) PasswordVisualTransformation() else VisualTransformation.None,
        readOnly = !editing,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            editing = false
            focusManager.moveFocus(FocusDirection.Down)
        }),
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged {
                if (!it.hasFocus) editing = false
            }
            .onPreviewKeyEvent { ev ->
                val code = ev.nativeKeyEvent.keyCode
                if (ev.type == KeyEventType.KeyUp) {
                    if (code == KeyEvent.KEYCODE_BACK && swallowBackUp) {
                        swallowBackUp = false
                        return@onPreviewKeyEvent true
                    }
                    // Up halves of keys handled on down are swallowed too.
                    return@onPreviewKeyEvent !editing && tvFieldKeyAction(false, code) != TvFieldKeyAction.Pass
                }
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (tvFieldKeyAction(editing, code)) {
                    TvFieldKeyAction.MoveUp -> focusManager.moveFocus(FocusDirection.Up)
                    TvFieldKeyAction.MoveDown -> focusManager.moveFocus(FocusDirection.Down)
                    TvFieldKeyAction.StartEdit -> {
                        editing = true
                        keyboard?.show()
                    }
                    TvFieldKeyAction.EndEdit -> {
                        swallowBackUp = true
                        endEdit()
                    }
                    TvFieldKeyAction.EndEditMoveUp -> {
                        endEdit()
                        focusManager.moveFocus(FocusDirection.Up)
                    }
                    TvFieldKeyAction.EndEditMoveDown -> {
                        endEdit()
                        focusManager.moveFocus(FocusDirection.Down)
                    }
                    TvFieldKeyAction.Pass -> return@onPreviewKeyEvent false
                }
                true
            },
    )
}

private const val IME_POLL_MS = 150L

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
