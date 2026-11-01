package dev.gamspi.videogallery.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text

@Composable
fun PlayerOverlay(
    title: String,
    positionMs: Long,
    durationMs: Long,
    playWhenReady: Boolean,
    buffering: Boolean,
    statusLine: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Color(0xB3000000))
            .padding(horizontal = 48.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, color = Color.White)
        val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        Box(Modifier.fillMaxWidth().height(4.dp).background(Color(0x66FFFFFF))) {
            Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(Color.White))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val time = formatTime(positionMs, durationMs) +
                if (durationMs > 0) " / " + formatTime(durationMs, durationMs) else ""
            val shown = when {
                !playWhenReady -> "Пауза · $time"
                buffering -> "Загрузка… · $time"
                else -> time
            }
            Text(shown, color = Color.White)
            Text("↓ Дорожки", color = Color(0xFFBBBBBB))
        }
        statusLine?.let { Text(it, color = Color(0xFFBBBBBB)) }
    }
}
