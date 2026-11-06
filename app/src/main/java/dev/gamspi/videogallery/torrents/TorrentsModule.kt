package dev.gamspi.videogallery.torrents

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.gamspi.videogallery.net.ProxyInterceptor
import dev.gamspi.videogallery.net.proxiedBy
import dev.gamspi.videogallery.settings.SettingsRepository
import dev.gamspi.videogallery.torrents.apibay.ApibayProvider
import dev.gamspi.videogallery.torrents.rutor.RutorProvider
import dev.gamspi.videogallery.torrents.rutracker.RutrackerCredentials
import dev.gamspi.videogallery.torrents.rutracker.RutrackerProvider
import dev.gamspi.videogallery.torrents.rutracker.RutrackerSession
import dev.gamspi.videogallery.torrents.yts.YtsProvider
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object TorrentsModule {
    @Provides
    @Singleton
    fun provideTorrentSearch(settings: SettingsRepository, proxy: ProxyInterceptor): TorrentSearch {
        val client = OkHttpClient.Builder()
            .callTimeout(15, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .proxiedBy(proxy)
            .build()
        fun url(id: ProviderId): () -> String = { settings.tracker(id).value.baseUrl }
        val all = listOf<TorrentProvider>(
            RutorProvider(client, url(ProviderId.Rutor)),
            ApibayProvider(client, url(ProviderId.Apibay)),
            YtsProvider(client, url(ProviderId.Yts)),
            RutrackerProvider(
                client,
                url(ProviderId.Rutracker),
                RutrackerSession(client, url(ProviderId.Rutracker)) {
                    RutrackerCredentials.of(settings.rutrackerUser, settings.rutrackerPassword)
                },
            ),
        )
        // Read settings at each search so changes apply without restart.
        return TorrentSearch(
            providers = { all.filter { settings.tracker(it.id).value.enabled } },
            resolvers = { all },
        )
    }
}
