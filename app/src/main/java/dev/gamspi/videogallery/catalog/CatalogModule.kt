package dev.gamspi.videogallery.catalog

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gamspi.videogallery.catalog.kinopoisk.KinopoiskCatalogRepository
import dev.gamspi.videogallery.catalog.tmdb.TmdbCatalogRepository
import dev.gamspi.videogallery.catalog.poiskkino.PoiskKinoCatalogRepository
import dev.gamspi.videogallery.net.ProxyInterceptor
import dev.gamspi.videogallery.net.proxiedBy
import dev.gamspi.videogallery.settings.SettingsRepository
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object CatalogModule {
    @Provides
    @Singleton
    fun provideCatalogHttpClient(@ApplicationContext context: Context, proxy: ProxyInterceptor): OkHttpClient =
        // Proxy first: credential interceptors added later must see the final (worker) origin.
        cachingClient(context.cacheDir).newBuilder().proxiedBy(proxy).build()

    @Provides
    @Singleton
    fun provideCatalogRepository(settings: SettingsRepository, client: OkHttpClient): CatalogRepository =
        SwitchingCatalogRepository(
            settings,
            TmdbCatalogRepository.create(TmdbCatalogRepository.BASE_URL, settings, client),
            KinopoiskCatalogRepository.create(KinopoiskCatalogRepository.BASE_URL, settings, client),
            PoiskKinoCatalogRepository.create(PoiskKinoCatalogRepository.BASE_URL, settings, client),
        )
}
