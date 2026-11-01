package dev.gamspi.videogallery.stream

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text

/** A recoverable warning: neither opening nor dismissing it stops playback. */
@Composable
fun StreamStallNotice(
    status: String?,
    onContinue: () -> Unit,
    onChooseAnother: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        focus.requestFocus()
    }
    Column(
        modifier.background(Color(0xF0101018)).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Загрузка нужных данных задерживается", color = Color.White)
        Text("Можно продолжить ожидание или выбрать другую раздачу.", color = Color.White)
        if (!status.isNullOrBlank()) Text(status, color = Color.White)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            StallButton("Продолжить ожидание", onContinue, Modifier.focusRequester(focus))
            StallButton("Выбрать другую раздачу", onChooseAnother)
        }
    }
}

@Composable
private fun StallButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.colors(
            containerColor = Color(0xFF34343C),
            contentColor = Color.White,
            focusedContainerColor = Color(0xFF23598A),
            focusedContentColor = Color.White,
        ),
    ) { Text(label, color = Color.White) }
}
