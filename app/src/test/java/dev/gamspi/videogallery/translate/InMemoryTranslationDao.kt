package dev.gamspi.videogallery.translate

/** Minimal in-memory DAO; counts insertCues calls. */
internal class InMemoryTranslationDao : TranslationDao {
    private val cues = mutableMapOf<Triple<Long, TranslateLang, String>, List<TranslatedCueEntity>>()
    private val entries = mutableMapOf<Triple<Long, TranslateLang, String>, CacheEntryEntity>()
    var inserts = 0

    override suspend fun cues(fileId: Long, target: TranslateLang, version: String) =
        cues[Triple(fileId, target, version)].orEmpty()

    override suspend fun cueCount(fileId: Long, target: TranslateLang, version: String) =
        cues(fileId, target, version).size

    override suspend fun entry(fileId: Long, target: TranslateLang, version: String) =
        entries[Triple(fileId, target, version)]

    override suspend fun touch(fileId: Long, target: TranslateLang, version: String, now: Long) {}

    override suspend fun insertCues(cues: List<TranslatedCueEntity>) {
        inserts++
        cues.groupBy { Triple(it.fileId, it.target, it.modelVersion) }.forEach { (k, v) ->
            this.cues[k] = this.cues[k].orEmpty().filter { old -> v.none { it.cueIndex == old.cueIndex } } + v
        }
    }

    override suspend fun upsertEntry(entry: CacheEntryEntity) {
        entries[Triple(entry.fileId, entry.target, entry.modelVersion)] = entry
    }

    override suspend fun deleteCues(fileId: Long, target: TranslateLang, version: String) {
        cues.remove(Triple(fileId, target, version))
    }

    override suspend fun deleteEntry(fileId: Long, target: TranslateLang, version: String) {
        entries.remove(Triple(fileId, target, version))
    }

    override suspend fun totalBytes() = entries.values.sumOf { it.bytes }

    override suspend fun oldestExcept(fileId: Long, target: TranslateLang, version: String) =
        entries.values.filter { it.fileId != fileId || it.target != target || it.modelVersion != version }
            .minByOrNull { it.lastUsedMs }

    override suspend fun clearCues() = cues.clear()

    override suspend fun clearEntries() = entries.clear()
}
