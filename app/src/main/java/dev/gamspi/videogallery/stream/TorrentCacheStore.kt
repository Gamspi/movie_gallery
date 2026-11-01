package dev.gamspi.videogallery.stream

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class TorrentStorageException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Only inactive, explicitly retained directories may survive a process restart. */
class TorrentCacheStore(
    private val root: File,
    private val freeBytes: () -> Long = { root.usableSpace },
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Serializable
    private data class SelectedFile(val index: Int, val path: String, val size: Long)

    @Serializable
    private data class Entry(val hash: String, val files: List<SelectedFile>, val lastUsed: Long)

    private val mutex = Mutex()
    private val retained = mutableMapOf<String, Entry>()
    private val active = mutableMapOf<String, Entry>()
    private var initialized = false
    private val json = Json { ignoreUnknownKeys = true }
    private val index get() = File(root, ".cache-index.json")

    suspend fun initialize() = access { initializeLocked() }

    suspend fun acquire(hash: String) = access {
        initializeLocked()
        validateHash(hash)
        if (hash in active) throw TorrentStorageException("Раздача ещё используется")
        directory(hash).let { if (!it.isDirectory && !it.mkdirs()) throw IOException("Cannot create torrent directory") }
        active[hash] = retained[hash]?.copy(lastUsed = now()) ?: Entry(hash, emptyList(), now())
    }

    suspend fun select(hash: String, index: Int, relativePath: String, size: Long, limitBytes: Long) = access {
        initializeLocked()
        val entry = active[hash] ?: throw TorrentStorageException("Раздача уже остановлена")
        if (index < 0 || size < 0) throw TorrentStorageException("Некорректный файл раздачи")
        checkedPayloadPath(hash, relativePath)
        val previous = entry.files.firstOrNull { it.index == index }
        if (previous != null && (previous.path != relativePath || previous.size != size)) {
            throw TorrentStorageException("Состав сохранённой раздачи изменился")
        }
        val candidate = entry.copy(
            files = entry.files.filterNot { it.index == index } + SelectedFile(index, relativePath, size),
            lastUsed = now(),
        )
        // Reserve the entire new file, including sparse/unwritten regions.
        val required = if (previous == null) size else 0L
        active[hash] = candidate
        try {
            enforceLimit(limitBytes)
            ensureFree(required)
        } catch (e: Exception) {
            active[hash] = entry
            throw e
        }
    }

    suspend fun checkSpace(hash: String, limitBytes: Long) = access {
        initializeLocked()
        if (hash !in active) throw TorrentStorageException("Раздача уже остановлена")
        enforceLimit(limitBytes)
        ensureFree(0)
    }

    /** The engine must have acknowledged removal before this is called. */
    suspend fun release(hash: String, keep: Boolean, limitBytes: Long) = access {
        initializeLocked()
        val entry = active.remove(hash) ?: return@access
        if (keep && entry.files.isNotEmpty()) {
            retained[hash] = entry.copy(lastUsed = now())
            saveIndex()
            enforceLimit(limitBytes)
        } else {
            retained.remove(hash)
            saveIndex()
            deleteTree(directory(hash))
        }
    }

    suspend fun clearInactive(): Long = access {
        initializeLocked()
        var cleared = 0L
        for (entry in retained.values.filter { it.hash !in active }.toList()) {
            cleared = addSaturated(cleared, payloadBytes(directory(entry.hash)))
            deleteTree(directory(entry.hash))
            retained.remove(entry.hash)
            saveIndex()
        }
        cleared
    }

    suspend fun usedBytes(): Long = access {
        initializeLocked()
        entries().fold(0L) { total, entry -> addSaturated(total, reservedBytes(entry)) }
    }

    private suspend fun <T> access(block: () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: TorrentStorageException) {
                throw e
            } catch (e: IOException) {
                throw TorrentStorageException("Не удалось записать файлы раздачи. Проверьте свободное место", e)
            }
        }
    }

    private fun initializeLocked() {
        if (initialized) return
        if (Files.isSymbolicLink(root.toPath())) throw IOException("Cache root is a symbolic link")
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Cannot create cache root")
        val records = try {
            if (index.isFile && !Files.isSymbolicLink(index.toPath()) && index.length() <= 1024 * 1024) {
                json.decodeFromString<List<Entry>>(index.readText(Charsets.UTF_8))
            } else emptyList()
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
        for (entry in records) {
            val valid = runCatching {
                validateHash(entry.hash)
                require(entry.files.isNotEmpty())
                require(entry.files.map { it.index }.distinct().size == entry.files.size)
                entry.files.forEach {
                    require(it.index >= 0 && it.size >= 0)
                    checkedPayloadPath(entry.hash, it.path)
                }
                directory(entry.hash).isDirectory
            }.getOrDefault(false)
            if (valid) retained[entry.hash] = entry
        }
        for (child in root.listFiles() ?: throw IOException("Cannot list cache root")) {
            if (child.name != index.name && child.name !in retained) deleteTree(child)
        }
        saveIndex()
        initialized = true
    }

    private fun validateHash(hash: String) {
        if (!HASH.matches(hash)) throw TorrentStorageException("Некорректный идентификатор раздачи")
    }

    private fun directory(hash: String): File {
        validateHash(hash)
        val dir = File(root, hash)
        if (Files.isSymbolicLink(dir.toPath()) || !dir.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) {
            throw IOException("Unsafe cache directory")
        }
        return dir
    }

    private fun checkedPayloadPath(hash: String, path: String): File {
        if (path.isBlank() || File(path).isAbsolute || path.split('/', '\\').any { it == ".." }) {
            throw TorrentStorageException("Небезопасный путь файла раздачи")
        }
        val dir = directory(hash).canonicalFile.toPath()
        val file = File(directory(hash), path)
        if (!file.canonicalFile.toPath().startsWith(dir) || file.canonicalFile.toPath() == dir) {
            throw TorrentStorageException("Небезопасный путь файла раздачи")
        }
        return file
    }

    private fun entries(): Collection<Entry> = (retained + active).values

    private fun reservedBytes(entry: Entry): Long = maxOf(
        entry.files.fold(0L) { sum, file -> addSaturated(sum, file.size) },
        payloadBytes(directory(entry.hash)),
    )

    private fun enforceLimit(limitBytes: Long) {
        if (limitBytes < 0) throw TorrentStorageException("Некорректный лимит хранения")
        if (active.values.fold(0L) { sum, entry -> addSaturated(sum, reservedBytes(entry)) } > limitBytes) {
            throw TorrentStorageException("Файл не помещается в лимит хранения. Увеличьте лимит или выберите другую раздачу")
        }
        while (entries().fold(0L) { sum, entry -> addSaturated(sum, reservedBytes(entry)) } > limitBytes) {
            evictOldest() ?: throw TorrentStorageException("Файл не помещается в лимит хранения. Увеличьте лимит или выберите другую раздачу")
        }
    }

    private fun ensureFree(required: Long) {
        while (freeBytes() < addSaturated(required, MIN_FREE_BYTES)) {
            evictOldest() ?: throw TorrentStorageException("Недостаточно свободного места. Очистите хранилище или выберите другую раздачу")
        }
    }

    private fun evictOldest(): Entry? {
        val entry = retained.values.filter { it.hash !in active }.minWithOrNull(compareBy<Entry> { it.lastUsed }.thenBy { it.hash }) ?: return null
        deleteTree(directory(entry.hash))
        retained.remove(entry.hash)
        saveIndex()
        return entry
    }

    private fun payloadBytes(file: File): Long {
        if (Files.isSymbolicLink(file.toPath())) throw IOException("Symbolic link in cache payload")
        if (!file.exists()) return 0
        if (file.isFile) return file.length()
        return (file.listFiles() ?: throw IOException("Cannot list cache payload"))
            .fold(0L) { sum, child -> addSaturated(sum, payloadBytes(child)) }
    }

    private fun deleteTree(file: File) {
        val path = file.toPath()
        if (Files.isSymbolicLink(path)) {
            Files.deleteIfExists(path)
            return
        }
        if (!file.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()) || file.canonicalFile == root.canonicalFile) {
            throw IOException("Refusing deletion outside cache payload")
        }
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            for (child in file.listFiles() ?: throw IOException("Cannot list cache payload")) deleteTree(child)
        }
        Files.deleteIfExists(path)
    }

    private fun saveIndex() {
        val temp = File(root, ".cache-index.tmp")
        if (Files.isSymbolicLink(temp.toPath()) || Files.isSymbolicLink(index.toPath())) throw IOException("Unsafe cache index")
        temp.writeText(json.encodeToString(retained.values.toList()), Charsets.UTF_8)
        try {
            Files.move(temp.toPath(), index.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), index.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val MIN_FREE_BYTES = 256L * 1024 * 1024
        private val HASH = Regex("[a-f0-9]{40}|[a-f0-9]{64}")
        private fun addSaturated(a: Long, b: Long): Long = if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b
    }
}
