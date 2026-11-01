package dev.gamspi.videogallery.stream

import android.content.Context
import java.io.File
import dev.gamspi.videogallery.settings.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
object StreamModule {
    @Provides
    @Singleton
    fun provideTorrentCacheStore(@ApplicationContext context: Context): TorrentCacheStore =
        TorrentCacheStore(File(context.filesDir, "torrents"))

    @Provides
    @Singleton
    fun provideTorrentEngine(): TorrentEngine = Libtorrent4jEngine()

    @Provides
    @Singleton
    fun provideStreamServer(): StreamServer =
        StreamServer(CoroutineScope(SupervisorJob() + Dispatchers.IO))

    @Provides
    @Singleton
    fun provideStreamManager(
        engine: TorrentEngine,
        server: StreamServer,
        @ApplicationContext context: Context,
        cache: TorrentCacheStore,
        settings: SettingsRepository,
    ): StreamManager = StreamManager(
        engine,
        server,
        context.filesDir,
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
        cache = cache,
        storagePrefs = { settings.torrentStoragePrefs.value },
    )
}
