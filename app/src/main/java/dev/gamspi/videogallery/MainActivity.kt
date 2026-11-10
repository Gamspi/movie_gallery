package dev.gamspi.videogallery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dagger.hilt.android.AndroidEntryPoint
import dev.gamspi.videogallery.watchlist.ListSyncService
import javax.inject.Inject
import dev.gamspi.videogallery.ui.nav.AppNavHost
import dev.gamspi.videogallery.ui.theme.VideoGalleryTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var listSync: ListSyncService

    override fun onStart() {
        super.onStart()
        listSync.setForeground(true)
    }

    override fun onStop() {
        listSync.setForeground(false)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VideoGalleryTheme {
                AppNavHost()
            }
        }
    }
}
