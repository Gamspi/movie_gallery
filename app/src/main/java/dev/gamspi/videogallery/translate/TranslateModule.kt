package dev.gamspi.videogallery.translate

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
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
object TranslateModule {
    @Provides
    @Singleton
    fun provideMlKitTranslator(): MlKitTranslator = MlKitTranslator()

    @Provides
    @Singleton
    fun provideSubtitleTranslator(translator: MlKitTranslator): SubtitleTranslator = translator

    @Provides
    @Singleton
    fun provideTranslationModels(translator: MlKitTranslator): TranslationModels =
        TranslationModelsImpl(
            backend = MlKitModelBackend(onDelete = translator::closeAll),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )

    @Provides
    @Singleton
    fun provideTranslationDatabase(@ApplicationContext context: Context): TranslationDatabase =
        Room.databaseBuilder(context, TranslationDatabase::class.java, "translation_cache.db").build()

    @Provides
    @Singleton
    fun provideTranslationCache(db: TranslationDatabase): TranslationCache =
        TranslationCache(dao = db.dao(), tx = { block -> db.withTransaction { block() } })
}
