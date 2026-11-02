package dev.gamspi.videogallery.translate

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TranslationDao {
    @Query("SELECT * FROM translated_cue WHERE fileId = :fileId AND target = :target AND modelVersion = :version")
    suspend fun cues(fileId: Long, target: TranslateLang, version: String): List<TranslatedCueEntity>

    @Query("SELECT COUNT(*) FROM translated_cue WHERE fileId = :fileId AND target = :target AND modelVersion = :version")
    suspend fun cueCount(fileId: Long, target: TranslateLang, version: String): Int

    @Query("SELECT * FROM cache_entry WHERE fileId = :fileId AND target = :target AND modelVersion = :version")
    suspend fun entry(fileId: Long, target: TranslateLang, version: String): CacheEntryEntity?

    @Query(
        "UPDATE cache_entry SET lastUsedMs = :now " +
            "WHERE fileId = :fileId AND target = :target AND modelVersion = :version",
    )
    suspend fun touch(fileId: Long, target: TranslateLang, version: String, now: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCues(cues: List<TranslatedCueEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEntry(entry: CacheEntryEntity)

    @Query("DELETE FROM translated_cue WHERE fileId = :fileId AND target = :target AND modelVersion = :version")
    suspend fun deleteCues(fileId: Long, target: TranslateLang, version: String)

    @Query("DELETE FROM cache_entry WHERE fileId = :fileId AND target = :target AND modelVersion = :version")
    suspend fun deleteEntry(fileId: Long, target: TranslateLang, version: String)

    @Query("SELECT COALESCE(SUM(bytes), 0) FROM cache_entry")
    suspend fun totalBytes(): Long

    /** Least recently used entry other than the given one. */
    @Query(
        "SELECT * FROM cache_entry WHERE NOT (fileId = :fileId AND target = :target AND modelVersion = :version) " +
            "ORDER BY lastUsedMs ASC LIMIT 1",
    )
    suspend fun oldestExcept(fileId: Long, target: TranslateLang, version: String): CacheEntryEntity?

    @Query("DELETE FROM translated_cue")
    suspend fun clearCues()

    @Query("DELETE FROM cache_entry")
    suspend fun clearEntries()
}
