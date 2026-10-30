package dev.gamspi.videogallery.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Text

/** Right-side panel with the audio track list. Keys other than Back are handled by D-pad focus. */
@Composable
fun AudioMenu(
    tracks: List<AudioTrack>,
    selected: Pair<Int, Int>?,
    onSelect: (AudioTrack) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    val target = tracks.firstOrNull { it.supported && (it.group to it.index) == selected }
        ?: tracks.firstOrNull { it.supported }
    LaunchedEffect(Unit) {
        if (target != null) focus.requestFocus()
    }
    Column(
        modifier
            .fillMaxHeight()
            .width(380.dp)
            .background(Color(0xE6101018))
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Аудио", color = Color.White)
        tracks.forEachIndexed { i, t ->
            val current = (t.group to t.index) == selected
            Button(
                onClick = { onSelect(t) },
                enabled = t.supported,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (t == target) Modifier.focusRequester(focus) else Modifier),
            ) {
                Text((if (current) "✓ " else "") + t.displayName(i + 1))
            }
        }
    }
}
