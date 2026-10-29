package dev.gamspi.videogallery.ui.theme

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme as M3MaterialTheme
import androidx.compose.material3.darkColorScheme as m3DarkColorScheme

@Composable
fun VideoGalleryTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        // Dark M3 scheme for compose-material3 widgets (OutlinedTextField).
        M3MaterialTheme(colorScheme = m3DarkColorScheme()) {
            // tv-material LocalContentColor defaults to black; Surface provides onSurface
            // (light) as content color for every Text outside Card/Button.
            Surface(modifier = Modifier.fillMaxSize()) {
                content()
            }
        }
    }
}
