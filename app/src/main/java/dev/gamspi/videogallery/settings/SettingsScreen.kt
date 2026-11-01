package dev.gamspi.videogallery.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import android.view.KeyEvent
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import dev.gamspi.videogallery.torrents.ProviderId
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onPlayTest: (String) -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    var testUrl by rememberSaveable { mutableStateOf(viewModel.testUrl.value.orEmpty()) }
    var sourceName by rememberSaveable { mutableStateOf(viewModel.catalogSource.value.name) }
    // Credentials persist only through SecretStore, never Activity saved state.
    var poiskkinoKey by remember { mutableStateOf(viewModel.poiskkinoApiKey.value.orEmpty()) }
    var kinopoiskKey by remember { mutableStateOf(viewModel.kinopoiskApiKey.value.orEmpty()) }
    var tmdbKey by remember { mutableStateOf(viewModel.tmdbApiKey.value.orEmpty()) }
    var osKey by remember { mutableStateOf(viewModel.openSubtitlesKey.orEmpty()) }
    var osUser by remember { mutableStateOf(viewModel.openSubtitlesUser.orEmpty()) }
    var osPassword by remember { mutableStateOf(viewModel.openSubtitlesPassword.orEmpty()) }
    val source = CatalogSource.entries.firstOrNull { it.name == sourceName } ?: CatalogSource.PoiskKino
    val trackerEnabled = ProviderId.entries.associateWith { id ->
        rememberSaveable(id.name) { mutableStateOf(viewModel.tracker(id).value.enabled) }
    }
    val trackerUrls = ProviderId.entries.associateWith { id ->
        rememberSaveable(id.name + "_url") { mutableStateOf(viewModel.tracker(id).value.baseUrl) }
    }
    val poiskkinoFocus = remember { FocusRequester() }
    val kinopoiskFocus = remember { FocusRequester() }
    val tmdbFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    var returnToPlay by rememberSaveable { mutableStateOf(false) }
    // Initial focus: «Играть» when returning from the player, else the button of the selected source.
    val initialSource = remember { source }
    LaunchedEffect(Unit) {
        when {
            returnToPlay -> playFocus.requestFocus()
            initialSource == CatalogSource.PoiskKino -> poiskkinoFocus.requestFocus()
            initialSource == CatalogSource.Kinopoisk -> kinopoiskFocus.requestFocus()
            else -> tmdbFocus.requestFocus()
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Настройки", style = MaterialTheme.typography.headlineMedium)
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
        Text("Трекеры", style = MaterialTheme.typography.titleLarge)
        ProviderId.entries.forEach { id ->
            val on = trackerEnabled.getValue(id).value
            SourceButton(id.label, on, null) { trackerEnabled.getValue(id).value = !on }
            TvTextField(
                value = trackerUrls.getValue(id).value,
                onValueChange = { trackerUrls.getValue(id).value = it },
                label = "Адрес ${id.label}",
            )
        }
        Text("Хранение торрентов", style = MaterialTheme.typography.titleLarge)
        val storage by viewModel.torrentStoragePrefs.collectAsState()
        val clearState by viewModel.torrentCacheClear.collectAsState()
        Button(onClick = viewModel::toggleTorrentRetention) {
            Text("После просмотра: ${if (storage.keepAfterPlayback) "хранить" else "удалять"}")
        }
        Text("Режим применяется при выходе из просмотра. Хранение не продолжает раздачу.")
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { viewModel.changeTorrentLimit(-1) }, enabled = storage.limitGiB > 1) { Text("−1 ГиБ") }
            Text("Лимит: ${storage.limitGiB} ГиБ")
            Button(onClick = { viewModel.changeTorrentLimit(1) }, enabled = storage.limitGiB < 100) { Text("+1 ГиБ") }
        }
        Text("При нехватке места удаляются самые старые сохранённые раздачи. Активная раздача не удаляется.")
        Button(onClick = viewModel::clearTorrentCache, enabled = !clearState.busy) {
            Text(if (clearState.busy) "Очищаем…" else "Очистить сохранённые торренты")
        }
        clearState.message?.let { Text(it) }
        Text("Субтитры", style = MaterialTheme.typography.titleLarge)
        val prefs by viewModel.subtitlePrefs.collectAsState()
        SubLayer.entries.forEach { layer ->
            val name = layer.name
            val isA = layer == SubLayer.A
            val lang = if (isA) prefs.languageA else prefs.languageB
            val style = if (isA) prefs.styleA else prefs.styleB
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = { viewModel.cycleLanguage(layer) }) { Text("Язык $name: $lang") }
                Button(onClick = { viewModel.cycleSize(layer) }) { Text("Размер $name: ${style.size.label}") }
                Button(onClick = { viewModel.cycleColor(layer) }) { Text("Цвет $name: ${style.color.label}") }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Сдвиг $name")
                Button(onClick = { viewModel.changeOffset(layer, -SUB_OFFSET_STEP_MS) }) { Text("−0,5 с") }
                Text(formatOffset(style.offsetMs))
                Button(onClick = { viewModel.changeOffset(layer, SUB_OFFSET_STEP_MS) }) { Text("+0,5 с") }
            }
        }
        TvTextField(
            value = osKey,
            onValueChange = { osKey = it },
            label = "OpenSubtitles ключ",
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
        Button(onClick = {
            viewModel.setOpenSubtitles(osKey, osUser, osPassword)
            val trackers = ProviderId.entries.associateWith {
                TrackerConfig(trackerEnabled.getValue(it).value, trackerUrls.getValue(it).value)
            }
            viewModel.save(source, poiskkinoKey, kinopoiskKey, tmdbKey, trackers)
            onBack()
        }) { Text("Сохранить") }
        TvTextField(
            value = testUrl,
            onValueChange = { testUrl = it },
            label = "Тестовый URL",
        )
        Button(
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
        modifier = Modifier
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
    Button(
        onClick = onClick,
        colors = ButtonDefaults.colors(
            containerColor = if (selected) Color(0xFF23598A) else Color(0xFF34343C),
            contentColor = Color.White,
            focusedContainerColor = Color(0xFF386DA0),
            focusedContentColor = Color.White,
        ),
        modifier = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier,
    ) { Text(if (selected) "✓ $label" else label, color = Color.White) }
}
