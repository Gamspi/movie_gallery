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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
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
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    var sourceName by rememberSaveable { mutableStateOf(viewModel.catalogSource.value.name) }
    var kinopoiskKey by rememberSaveable { mutableStateOf(viewModel.kinopoiskApiKey.value.orEmpty()) }
    var tmdbKey by rememberSaveable { mutableStateOf(viewModel.tmdbApiKey.value.orEmpty()) }
    val source = CatalogSource.valueOf(sourceName)
    val trackerEnabled = remember {
        mutableStateMapOf<ProviderId, Boolean>().apply {
            ProviderId.entries.forEach { put(it, viewModel.tracker(it).value.enabled) }
        }
    }
    val trackerUrls = remember {
        mutableStateMapOf<ProviderId, String>().apply {
            ProviderId.entries.forEach { put(it, viewModel.tracker(it).value.baseUrl) }
        }
    }
    val kinopoiskFocus = remember { FocusRequester() }
    val tmdbFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    // Initial focus on the button of the source selected when the screen opened.
    val initialSource = remember { source }
    LaunchedEffect(Unit) {
        if (initialSource == CatalogSource.Kinopoisk) kinopoiskFocus.requestFocus() else tmdbFocus.requestFocus()
    }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Настройки", style = MaterialTheme.typography.headlineMedium)
        Text("Источник каталога")
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SourceButton("Кинопоиск", source == CatalogSource.Kinopoisk, kinopoiskFocus) {
                sourceName = CatalogSource.Kinopoisk.name
            }
            SourceButton("TMDB", source == CatalogSource.Tmdb, tmdbFocus) {
                sourceName = CatalogSource.Tmdb.name
            }
        }
        OutlinedTextField(
            value = kinopoiskKey,
            onValueChange = { kinopoiskKey = it },
            label = { Text("Ключ Кинопоиска") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.moveFocus(FocusDirection.Down) }),
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Получить: kinopoiskapiunofficial.tech → Профиль")
        OutlinedTextField(
            value = tmdbKey,
            onValueChange = { tmdbKey = it },
            label = { Text("Ключ TMDB API") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.moveFocus(FocusDirection.Down) }),
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Получить: themoviedb.org → Settings → API")
        Text("Трекеры", style = MaterialTheme.typography.titleLarge)
        ProviderId.entries.forEach { id ->
            val on = trackerEnabled.getValue(id)
            SourceButton(id.label, on, null) { trackerEnabled[id] = !on }
            OutlinedTextField(
                value = trackerUrls.getValue(id),
                onValueChange = { trackerUrls[id] = it },
                label = { Text("Адрес ${id.label}") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.moveFocus(FocusDirection.Down) }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Button(onClick = {
            val trackers = ProviderId.entries.associateWith {
                TrackerConfig(trackerEnabled.getValue(it), trackerUrls.getValue(it))
            }
            viewModel.save(source, kinopoiskKey, tmdbKey, trackers)
            onBack()
        }) { Text("Сохранить") }
    }
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
        colors = if (selected) {
            ButtonDefaults.colors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            ButtonDefaults.colors()
        },
        modifier = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier,
    ) { Text(if (selected) "✓ $label" else label) }
}
