package dev.gamspi.videogallery.history

import android.content.Context
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
object HistoryModule {
    @Provides
    @Singleton
    fun provideSeriesStore(@ApplicationContext context: Context): SeriesStore =
        PersistentSeriesStore.fromPrefs(
            { context.getSharedPreferences("series", Context.MODE_PRIVATE) },
            CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )

    @Provides
    @Singleton
    fun provideWatchHistoryStore(@ApplicationContext context: Context): WatchHistoryStore =
        PersistentWatchHistoryStore.fromPrefs(
            { context.getSharedPreferences("watch_history", Context.MODE_PRIVATE) },
            CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
}
