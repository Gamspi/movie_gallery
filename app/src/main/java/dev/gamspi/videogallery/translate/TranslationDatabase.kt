package dev.gamspi.videogallery.translate

import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Entity
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

@Entity(
    tableName = "translated_cue",
    primaryKeys = ["fileId", "target", "modelVersion", "cueIndex"],
)
data class TranslatedCueEntity(
    val fileId: Long,
    val target: TranslateLang,
    val modelVersion: String,
    val cueIndex: Int,
    val text: String,
)

@Entity(
    tableName = "cache_entry",
    primaryKeys = ["fileId", "target", "modelVersion"],
)
data class CacheEntryEntity(
    val fileId: Long,
    val target: TranslateLang,
    val modelVersion: String,
    /** Sum of UTF-8 byte lengths of the cached texts. */
    val bytes: Long,
    @ColumnInfo(index = true) val lastUsedMs: Long,
)

class TranslateLangConverters {
    @TypeConverter
    fun toTag(lang: TranslateLang): String = lang.tag

    @TypeConverter
    fun fromTag(tag: String): TranslateLang = TranslateLang.entries.first { it.tag == tag }
}

// Version 1: no migrations yet; a schema change must add an explicit Migration (no destructive fallback).
@Database(
    entities = [TranslatedCueEntity::class, CacheEntryEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(TranslateLangConverters::class)
abstract class TranslationDatabase : RoomDatabase() {
    abstract fun dao(): TranslationDao
}
