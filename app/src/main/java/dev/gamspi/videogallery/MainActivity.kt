package dev.gamspi.videogallery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.tv.material3.Text
import dagger.hilt.android.AndroidEntryPoint
import dev.gamspi.videogallery.ui.theme.VideoGalleryTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VideoGalleryTheme {
                Text("Video Gallery")
            }
        }
    }
}
