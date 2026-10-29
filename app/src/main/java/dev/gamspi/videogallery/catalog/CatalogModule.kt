package dev.gamspi.videogallery.catalog

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.gamspi.videogallery.catalog.kinopoisk.KinopoiskCatalogRepository
import dev.gamspi.videogallery.catalog.tmdb.TmdbCatalogRepository
import dev.gamspi.videogallery.settings.SettingsRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CatalogModule {
    @Provides
    @Singleton
    fun provideCatalogRepository(settings: SettingsRepository): CatalogRepository =
        SwitchingCatalogRepository(
            settings,
            TmdbCatalogRepository.create(TmdbCatalogRepository.BASE_URL, settings),
            KinopoiskCatalogRepository.create(KinopoiskCatalogRepository.BASE_URL, settings),
        )
}
