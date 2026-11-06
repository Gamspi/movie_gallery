package dev.gamspi.videogallery.net

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.gamspi.videogallery.settings.SettingsRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetModule {
    /** Shared proxy; reads current settings on every request, so toggles apply without restart. */
    @Provides
    @Singleton
    fun provideProxyInterceptor(settings: SettingsRepository): ProxyInterceptor =
        ProxyInterceptor(settings::proxyConfig)
}
