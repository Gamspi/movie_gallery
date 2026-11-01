package dev.gamspi.videogallery.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import dev.gamspi.videogallery.settings.SUB_OFFSET_MAX_MS
import dev.gamspi.videogallery.settings.SUB_OFFSET_STEP_MS
import dev.gamspi.videogallery.subs.Layer
import dev.gamspi.videogallery.subs.LayerSource
import dev.gamspi.videogallery.subs.LayerState
import dev.gamspi.videogallery.subs.RemoteSubtitle
import dev.gamspi.videogallery.subs.subtitleLanguage
import kotlin.math.abs

data class SubtitleMenuOption(
    val label: String,
    val source: LayerSource?,
    val enabled: Boolean = true,
)

fun layerOptions(
    language: String,
    embedded: List<TextTrack>,
    remote: List<RemoteSubtitle>,
    note: String? = null,
): List<SubtitleMenuOption> = buildList {
    add(SubtitleMenuOption("Выкл", LayerSource.Off))
    embedded.forEachIndexed { ordinal, track ->
        val name = listOfNotNull(track.language, track.label).filter { it.isNotBlank() }
            .joinToString(" · ").ifBlank { "Дорожка ${ordinal + 1}" }
        add(SubtitleMenuOption(name, LayerSource.Embedded(track), track.supported))
    }
    val normalized = subtitleLanguage(language)
    remote.filter { normalized != null && subtitleLanguage(it.language) == normalized }
        .distinctBy { it.fileId }.take(5).forEach {
            add(SubtitleMenuOption("OpenSubtitles · ${it.release.ifBlank { it.language }}", LayerSource.Remote(it)))
        }
    if (size == 1 || note != null) {
        add(SubtitleMenuOption(note ?: "нет субтитров", null, false))
    }
}

fun formatOffset(ms: Long): String {
    if (ms == 0L) return "0 с"
    val magnitude = abs(ms)
    val fraction = (magnitude % 1000) / 100
    val value = "${magnitude / 1000}" + if (fraction == 0L) "" else ",$fraction"
    return (if (ms > 0) "+" else "−") + value + " с"
}

/** Focus owns navigation while the panel is open; only Back belongs to the screen. */
@Composable
fun TracksMenu(
    audioTracks: List<AudioTrack>,
    selectedAudio: Pair<Int, Int>?,
    onAudioSelect: (AudioTrack) -> Unit,
    embedded: List<TextTrack>,
    remoteOptions: List<RemoteSubtitle>,
    layers: Map<Layer, LayerState>,
    languageA: String,
    languageB: String,
    onSelect: (Layer, LayerSource) -> Unit,
    onShift: (Layer, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    val targetAudio = audioTracks.firstOrNull { it.supported && (it.group to it.index) == selectedAudio }
        ?: audioTracks.firstOrNull { it.supported }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(
        modifier.fillMaxHeight().width(420.dp).background(Color(0xE6101018))
            .padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Аудио", color = Color.White)
        if (audioTracks.isEmpty()) Text("нет аудиодорожек", color = Color.LightGray)
        audioTracks.forEachIndexed { ordinal, track ->
            MenuButton(
                label = (if ((track.group to track.index) == selectedAudio) "✓ " else "") + track.displayName(ordinal + 1),
                onClick = { onAudioSelect(track) },
                enabled = track.supported,
                modifier = Modifier.fillMaxWidth()
                    .then(if (track == targetAudio) Modifier.focusRequester(focus) else Modifier),
            )
        }
        Layer.entries.forEach { layer ->
            val language = if (layer == Layer.A) languageA else languageB
            val state = layers[layer] ?: LayerState()
            Text("Субтитры ${layer.name} ($language)", color = Color.White)
            layerOptions(language, embedded, remoteOptions, state.note).forEachIndexed { index, option ->
                if (option.source == null) {
                    Text(option.label, color = Color.LightGray)
                    return@forEachIndexed
                }
                MenuButton(
                    label = (if (option.source == state.source) "✓ " else "") + option.label,
                    onClick = { onSelect(layer, option.source) },
                    enabled = option.enabled,
                    modifier = Modifier.fillMaxWidth().then(
                        if (targetAudio == null && layer == Layer.A && index == 0) Modifier.focusRequester(focus)
                        else Modifier,
                    ),
                )
            }
            if (state.source is LayerSource.Embedded) {
                Text("Сдвиг встроенной дорожки недоступен", color = Color.LightGray)
            }
        }
        val external = Layer.entries.filter { layers[it]?.source is LayerSource.Remote }
        if (external.isNotEmpty()) Text("Сдвиг", color = Color.White)
        external.forEach { layer ->
            val offset = layers.getValue(layer).offsetMs
            Text("${layer.name}: ${formatOffset(offset)}", color = Color.White)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MenuButton(
                    label = "−0,5 с",
                    onClick = { onShift(layer, -SUB_OFFSET_STEP_MS) },
                    enabled = offset > -SUB_OFFSET_MAX_MS,
                )
                MenuButton(
                    label = "+0,5 с",
                    onClick = { onShift(layer, SUB_OFFSET_STEP_MS) },
                    enabled = offset < SUB_OFFSET_MAX_MS,
                )
            }
        }
    }
}

@Composable
private fun MenuButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.colors(
            containerColor = Color(0xFF34343C),
            contentColor = Color.White,
            focusedContainerColor = Color(0xFF23598A),
            focusedContentColor = Color.White,
        ),
    ) {
        Text(label, color = if (enabled) Color.White else Color.Gray)
    }
}
