package dev.gamspi.videogallery.stream

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.gamspi.videogallery.torrents.formatSize
import kotlinx.coroutines.flow.first

@Composable
fun StreamScreen(
    onReady: (url: String, key: String, title: String, imdb: String) -> Unit,
    onBack: () -> Unit,
    viewModel: StreamViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val stallWarning by viewModel.stallWarning.collectAsStateWithLifecycle(initialValue = null)
    var stallDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(stallWarning) { stallDismissed = false }
    val lifecycleOwner = LocalLifecycleOwner.current

    fun back() {
        viewModel.cancel()
        onBack()
    }

    BackHandler { back() }

    val ready = state as? StreamState.Ready
    if (ready != null) {
        // Keyed on the Ready value: fires once per Ready, never on plain recomposition.
        // Waits for RESUMED: navigateOnce drops the call otherwise (enter transition, back from Home).
        LaunchedEffect(ready) {
            lifecycleOwner.lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
            onReady(ready.url, ready.key, ready.title, viewModel.imdb)
        }
    }

    Box(Modifier.fillMaxSize().padding(48.dp), contentAlignment = Alignment.Center) {
        when (val s = state) {
            StreamState.Idle, StreamState.FetchingMetadata, is StreamState.Ready ->
                Text("Получаем данные раздачи…", style = MaterialTheme.typography.titleMedium)
            is StreamState.Buffering -> Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Буферизация ${s.percent} %", style = MaterialTheme.typography.titleMedium)
                if (s.status.isNotBlank()) Text(s.status, style = MaterialTheme.typography.bodyMedium)
                if (stallWarning != null && !stallDismissed) {
                    StreamStallNotice(
                        status = null,
                        onContinue = { stallDismissed = true },
                        onChooseAnother = ::back,
                    )
                }
            }
            is StreamState.ChooseFile -> ChooseFileList(s.files, viewModel::chooseFile)
            is StreamState.Failed -> FailedContent(s.message, ::back)
        }
    }
}

@Composable
private fun ChooseFileList(files: List<TorrentFile>, onChoose: (Int) -> Unit) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstFocus.requestFocus() }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Выберите файл", style = MaterialTheme.typography.headlineMedium)
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(files, key = { _, f -> f.index }) { index, f ->
                ListItem(
                    selected = false,
                    onClick = { onChoose(f.index) },
                    headlineContent = {
                        Text(
                            "${f.path.substringAfterLast('/')} · ${formatSize(f.size)}",
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                        .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier),
                )
            }
        }
    }
}

@Composable
private fun FailedContent(message: String, onBack: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, style = MaterialTheme.typography.titleMedium)
        Button(onClick = onBack, modifier = Modifier.focusRequester(focus)) { Text("Назад") }
    }
}
