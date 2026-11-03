package dev.gamspi.videogallery.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import dev.gamspi.videogallery.settings.SUB_OFFSET_MAX_MS
import dev.gamspi.videogallery.settings.SUB_OFFSET_STEP_MS
import dev.gamspi.videogallery.subs.Layer
import dev.gamspi.videogallery.subs.LayerSource
import dev.gamspi.videogallery.subs.LayerState
import dev.gamspi.videogallery.subs.RemoteSubtitle
import dev.gamspi.videogallery.subs.subtitleLanguage
import dev.gamspi.videogallery.ui.icons.AppIcons
import dev.gamspi.videogallery.ui.theme.AppColors
import kotlin.math.abs

enum class PlayerMenuSection(val title: String) {
    Subtitles("Субтитры"),
    Audio("Аудиодорожка"),
    Other("Прочее"),
}

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
    translations: List<LayerSource.Translated> = emptyList(),
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
    translations.forEach { add(SubtitleMenuOption("Перевод с ${it.sourceLang.tag}", it)) }
    if (size == 1 || note != null) {
        add(SubtitleMenuOption(note ?: "нет субтитров", null, false))
    }
    if (translations.isEmpty() && note != NO_TRANSLATION_SOURCE) {
        add(SubtitleMenuOption(NO_TRANSLATION_SOURCE, null, false))
    }
}

private const val NO_TRANSLATION_SOURCE = "Нет субтитров для перевода"

fun formatOffset(ms: Long): String {
    if (ms == 0L) return "0 с"
    val magnitude = abs(ms)
    val fraction = (magnitude % 1000) / 100
    val value = "${magnitude / 1000}" + if (fraction == 0L) "" else ",$fraction"
    return (if (ms > 0) "+" else "−") + value + " с"
}

/**
 * Focus owns Up/Down/OK while the panel is open; Back belongs to the screen.
 * [section] == null shows the section list, otherwise that section's options.
 */
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
    translations: Map<Layer, List<LayerSource.Translated>>,
    onSelect: (Layer, LayerSource) -> Unit,
    onShift: (Layer, Long) -> Unit,
    section: PlayerMenuSection?,
    onOpenSection: (PlayerMenuSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sectionFocus = remember { PlayerMenuSection.entries.associateWith { FocusRequester() } }
    val contentFocus = remember { FocusRequester() }
    var lastSection by remember { mutableStateOf(PlayerMenuSection.Subtitles) }
    LaunchedEffect(section) {
        // Returning to the list focuses the section the user came from.
        if (section == null) sectionFocus.getValue(lastSection).requestFocus() else contentFocus.requestFocus()
    }
    Column(
        modifier.fillMaxHeight().width(420.dp).background(Color(0xE6101018))
            .padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (section) {
            null -> {
                Text("Настройки просмотра", color = Color.White)
                PlayerMenuSection.entries.forEach { entry ->
                    MenuButton(
                        label = entry.title,
                        onClick = {
                            lastSection = entry
                            onOpenSection(entry)
                        },
                        enabled = true,
                        trailingChevron = true,
                        modifier = Modifier.fillMaxWidth().focusRequester(sectionFocus.getValue(entry)),
                    )
                }
            }
            PlayerMenuSection.Subtitles -> SubtitlesSection(
                embedded, remoteOptions, layers, languageA, languageB, translations, onSelect, contentFocus,
            )
            PlayerMenuSection.Audio -> AudioSection(audioTracks, selectedAudio, onAudioSelect, contentFocus)
            PlayerMenuSection.Other -> OtherSection(layers, onShift, contentFocus)
        }
    }
}

@Composable
private fun SubtitlesSection(
    embedded: List<TextTrack>,
    remoteOptions: List<RemoteSubtitle>,
    layers: Map<Layer, LayerState>,
    languageA: String,
    languageB: String,
    translations: Map<Layer, List<LayerSource.Translated>>,
    onSelect: (Layer, LayerSource) -> Unit,
    focus: FocusRequester,
) {
    Text(PlayerMenuSection.Subtitles.title, color = Color.White)
    Layer.entries.forEach { layer ->
        val language = if (layer == Layer.A) languageA else languageB
        val state = layers[layer] ?: LayerState()
        val options = layerOptions(language, embedded, remoteOptions, state.note, translations[layer].orEmpty())
        // Focus lands on layer A's current option, or on "Выкл" when none matches.
        val target = if (layer != Layer.A) null else
            options.firstOrNull { it.source != null && it.enabled && it.source == state.source } ?: options.first()
        Text("Слой ${layer.name} ($language)", color = Color.LightGray)
        options.forEach { option ->
            val source = option.source
            if (source == null) {
                Text(option.label, color = Color.LightGray)
                return@forEach
            }
            MenuButton(
                label = option.label,
                onClick = { onSelect(layer, source) },
                enabled = option.enabled,
                current = source == state.source,
                modifier = Modifier.fillMaxWidth()
                    .then(if (option === target) Modifier.focusRequester(focus) else Modifier),
            )
        }
    }
}

@Composable
private fun AudioSection(
    audioTracks: List<AudioTrack>,
    selectedAudio: Pair<Int, Int>?,
    onAudioSelect: (AudioTrack) -> Unit,
    focus: FocusRequester,
) {
    Text(PlayerMenuSection.Audio.title, color = Color.White)
    val target = audioTracks.firstOrNull { it.supported && (it.group to it.index) == selectedAudio }
        ?: audioTracks.firstOrNull { it.supported }
    if (target == null) MenuNote("нет аудиодорожек", focus)
    audioTracks.forEachIndexed { ordinal, track ->
        MenuButton(
            label = track.displayName(ordinal + 1),
            onClick = { onAudioSelect(track) },
            enabled = track.supported,
            current = (track.group to track.index) == selectedAudio,
            modifier = Modifier.fillMaxWidth()
                .then(if (track == target) Modifier.focusRequester(focus) else Modifier),
        )
    }
}

@Composable
private fun OtherSection(
    layers: Map<Layer, LayerState>,
    onShift: (Layer, Long) -> Unit,
    focus: FocusRequester,
) {
    Text("Сдвиг субтитров", color = Color.White)
    // Translated layers follow the source offset, so only Remote layers get controls.
    val external = Layer.entries.filter { layers[it]?.source is LayerSource.Remote }
    val embeddedLayers = Layer.entries.filter { layers[it]?.source is LayerSource.Embedded }
    if (external.isEmpty()) {
        MenuNote(
            if (embeddedLayers.isNotEmpty()) "Сдвиг встроенной дорожки недоступен"
            else "Нет внешних субтитров для сдвига",
            focus,
        )
    } else {
        embeddedLayers.forEach {
            Text("${it.name}: сдвиг встроенной дорожки недоступен", color = Color.LightGray)
        }
    }
    external.forEachIndexed { index, layer ->
        val offset = layers.getValue(layer).offsetMs
        Text("${layer.name}: ${formatOffset(offset)}", color = Color.White)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MenuButton(
                label = "−0,5 с",
                // Stays enabled at the limit so focus is never lost; the click is a no-op there.
                onClick = { if (offset > -SUB_OFFSET_MAX_MS) onShift(layer, -SUB_OFFSET_STEP_MS) },
                enabled = true,
                modifier = if (index == 0) Modifier.focusRequester(focus) else Modifier,
            )
            MenuButton(
                label = "+0,5 с",
                onClick = { if (offset < SUB_OFFSET_MAX_MS) onShift(layer, SUB_OFFSET_STEP_MS) },
                enabled = true,
            )
        }
    }
}

/** Focusable plain text, so a section without options still holds remote focus. */
@Composable
private fun MenuNote(text: String, focus: FocusRequester) {
    Text(text, color = Color.LightGray, modifier = Modifier.focusRequester(focus).focusable())
}

@Composable
private fun MenuButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    current: Boolean = false,
    trailingChevron: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.colors(
            containerColor = Color(0xFF34343C),
            contentColor = if (current) AppColors.Accent else Color.White,
            focusedContainerColor = AppColors.Accent,
            focusedContentColor = AppColors.OnAccent,
            disabledContainerColor = Color(0xFF26262C),
            disabledContentColor = Color.Gray,
        ),
    ) {
        Text(
            (if (current) "✓ " else "") + label,
            modifier = if (trailingChevron) Modifier.weight(1f) else Modifier,
        )
        if (trailingChevron) Icon(AppIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(20.dp))
    }
}
