package dev.gamspi.videogallery.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.Text

/** Placeholder; replaced by the real player in Task 3. */
@Composable
fun PlayerScreen(onBack: () -> Unit) {
    val state by hiltViewModel<PlayerViewModel>().state.collectAsState()
    Box(Modifier.fillMaxSize()) { Text(state.title) }
}
