package dev.gamspi.videogallery

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Leftovers of torrent sessions after a crash.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { File(filesDir, "torrents").deleteRecursively() }
        }
    }
}
