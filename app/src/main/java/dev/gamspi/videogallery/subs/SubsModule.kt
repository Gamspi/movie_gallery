package dev.gamspi.videogallery.subs

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.gamspi.videogallery.settings.SettingsRepository
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object SubsModule {
    @Provides
    @Singleton
    fun provideSubtitleSettingsSource(settings: SettingsRepository): SubtitleSettingsSource = settings

    @Provides
    @Singleton
    fun provideOpenSubtitlesClient(settings: SubtitleSettingsSource): OpenSubtitlesClient =
        OpenSubtitlesClient(
            baseUrl = "https://api.opensubtitles.com/api/v1/",
            settings = settings,
            http = OkHttpClient.Builder()
                .callTimeout(20, TimeUnit.SECONDS)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build(),
        )
}
