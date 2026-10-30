package dev.gamspi.videogallery.player

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PlayerModule {
    @Provides
    @Singleton
    fun providePlaybackStore(@ApplicationContext context: Context): PlaybackStore =
        SharedPrefsPlaybackStore(context.getSharedPreferences("playback", Context.MODE_PRIVATE))
}
