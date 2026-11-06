package dev.gamspi.videogallery.gallery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Self-contained HH:mm clock; only this composable recomposes on the minute tick. */
@Composable
fun HeaderClock(modifier: Modifier = Modifier) {
    val formatter = remember { DateTimeFormatter.ofPattern("HH:mm") }
    var text by remember { mutableStateOf(LocalTime.now().format(formatter)) }
    LaunchedEffect(Unit) {
        while (true) {
            val now = LocalTime.now()
            text = now.format(formatter)
            // Sleep until the next minute boundary.
            delay(((60 - now.second) * 1000L - now.nano / 1_000_000L).coerceAtLeast(50L))
        }
    }
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.secondary,
        modifier = modifier,
    )
}
