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
        // Leftovers of torrent sessions after a crash. Rename synchronously, so that files of a
        // session started right after cannot be hit by the background delete.
        val torrents = File(filesDir, "torrents")
        if (torrents.exists()) {
            runCatching { torrents.renameTo(File(filesDir, "torrents.trash-${System.currentTimeMillis()}")) }
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            filesDir.listFiles { f -> f.name.startsWith("torrents.trash-") }
                ?.forEach { runCatching { it.deleteRecursively() } }
        }
    }
}
