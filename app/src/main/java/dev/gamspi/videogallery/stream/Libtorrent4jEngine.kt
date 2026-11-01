package dev.gamspi.videogallery.stream

import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.AlertListener
import org.libtorrent4j.InfoHash
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.AddTorrentAlert
import org.libtorrent4j.alerts.FileErrorAlert
import org.libtorrent4j.alerts.MetadataReceivedAlert
import org.libtorrent4j.alerts.PieceFinishedAlert
import org.libtorrent4j.alerts.TorrentDeleteFailedAlert
import org.libtorrent4j.alerts.TorrentDeletedAlert
import org.libtorrent4j.alerts.TorrentRemovedAlert
import org.libtorrent4j.alerts.TorrentPausedAlert
import org.libtorrent4j.swig.alert as swigAlert
import org.libtorrent4j.swig.settings_pack

private const val UPLOAD_LIMIT_BYTES = 1024 * 1024
private const val REMOVE_WAIT_MS = 5_000L
private const val POLL_MS = 1_000L
private const val ADD_WAIT_MS = 5_000
private const val ADD_POLL_MS = 50L

/** One hash rule everywhere: v1 if present, else the best (truncated v2). */
private fun InfoHash.key(): String = (if (hasV1()) v1 else best).toHex().lowercase()

private class PendingRemoval(val deleteFiles: Boolean) {
    val done = CompletableDeferred<Unit>()
}

/** Движок на libtorrent4j. Сессия стартует лениво при первом [add]. */
class Libtorrent4jEngine : TorrentEngine {
    private val sm = SessionManager(false)
    private val startLock = Any()
    private val metadata = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val removing = ConcurrentHashMap<String, PendingRemoval>()
    private val paused = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val storageErrors = ConcurrentHashMap<String, TorrentStorageException>()
    private val uncertainAdds = ConcurrentHashMap.newKeySet<String>()
    private val pieces = MutableSharedFlow<Pair<String, Int>>(extraBufferCapacity = 1024)

    override val pieceFinished: SharedFlow<Pair<String, Int>> = pieces.asSharedFlow()

    private val listener = object : AlertListener {
        override fun types(): IntArray = intArrayOf(
            AlertType.PIECE_FINISHED.swig(),
            AlertType.METADATA_RECEIVED.swig(),
            AlertType.TORRENT_REMOVED.swig(),
            AlertType.TORRENT_DELETED.swig(),
            AlertType.TORRENT_DELETE_FAILED.swig(),
            AlertType.TORRENT_PAUSED.swig(),
            AlertType.FILE_ERROR.swig(),
            AlertType.ADD_TORRENT.swig(),
        )

        override fun alert(alert: Alert<*>) {
            when (alert) {
                is AddTorrentAlert -> {
                    val h = alert.handle()
                    if (h.isValid && keyOf(h) in uncertainAdds) h.pause()
                }
                is PieceFinishedAlert -> pieces.tryEmit(keyOf(alert.handle()) to alert.pieceIndex())
                is MetadataReceivedAlert -> {
                    val hash = keyOf(alert.handle())
                    metadata.getOrPut(hash) { CompletableDeferred() }.complete(Unit)
                }
                is TorrentPausedAlert -> paused.getOrPut(keyOf(alert.handle())) { CompletableDeferred() }.complete(Unit)
                is FileErrorAlert -> storageErrors[keyOf(alert.handle())] =
                    TorrentStorageException("Не удалось записать файлы раздачи. Освободите место или выберите другую раздачу")
                is TorrentRemovedAlert -> finishRemoval(alert.infoHashes.key(), deleted = false)
                is TorrentDeletedAlert -> finishRemoval(alert.infoHashes.key(), deleted = true)
                is TorrentDeleteFailedAlert -> removing[alert.infoHashes.key()]?.done?.completeExceptionally(IOException("Torrent file deletion failed"))
                else -> Unit
            }
        }
    }

    /** Same key rule as add/remove (TorrentHandle has no infoHashes(); use the torrent info). */
    private fun keyOf(h: TorrentHandle): String =
        runCatching { h.torrentFile()?.infoHashes()?.key() }.getOrNull() ?: h.infoHash().toHex().lowercase()

    private fun finishRemoval(hash: String, deleted: Boolean) {
        val p = removing[hash] ?: return
        if (p.deleteFiles == deleted) {
            p.done.complete(Unit)
        }
    }

    private fun ensureStarted() {
        synchronized(startLock) {
            if (sm.isRunning) return
            val settings = SettingsPack().uploadRateLimit(UPLOAD_LIMIT_BYTES)
            settings.setEnableDht(true)
            settings.setEnableLsd(true)
            // Without an explicit mask piece_finished / metadata_received alerts may never be posted.
            val mask = swigAlert.status_notification
                .or_(swigAlert.storage_notification)
                .or_(swigAlert.piece_progress_notification)
                .or_(swigAlert.error_notification)
            settings.setInteger(settings_pack.int_types.alert_mask.swigValue(), mask.to_int())
            settings.setString(
                settings_pack.string_types.dht_bootstrap_nodes.swigValue(),
                "dht.libtorrent.org:25401,router.bittorrent.com:6881,router.utorrent.com:6881,dht.transmissionbt.com:6881",
            )
            sm.addListener(listener)
            sm.start(SessionParams(settings))
        }
    }

    private fun handle(hash: String): TorrentHandle =
        sm.find(Sha1Hash.parseHex(hash)) ?: error("torrent not found: $hash")

    private fun ignoreAll(h: TorrentHandle) {
        val n = h.torrentFile()?.numFiles() ?: return
        h.prioritizeFiles(Array(n) { Priority.IGNORE })
    }

    override suspend fun add(magnet: String, saveDir: File): String {
        ensureStarted()
        val hash = hashOf(magnet)
        check(hash !in uncertainAdds) { "Previous add is unresolved" }
        // A previous session of the same torrent may still be shutting down.
        removing[hash]?.let { pending ->
            check(withTimeoutOrNull(REMOVE_WAIT_MS) { pending.done.await(); true } == true) { "Previous torrent is still stopping" }
            removing.remove(hash, pending)
        }
        val dir = File(saveDir, hash).also { it.mkdirs() }
        paused.remove(hash)
        storageErrors.remove(hash)
        metadata.getOrPut(hash) { CompletableDeferred() }
        val params = AddTorrentParams.parseMagnetUri(magnet)
        params.savePath = dir.absolutePath
        // Metadata is allowed, payload requests wait for storage admission and file selection.
        params.flags = params.flags.and_(TorrentFlags.AUTO_MANAGED.inv())
            .and_(TorrentFlags.PAUSED.inv()).or_(TorrentFlags.UPLOAD_MODE)
        SessionHandle(sm.swig()).asyncAddTorrent(params)
        // download() is asynchronous: wait until the handle exists, or remove() could not find it.
        val sha = Sha1Hash.parseHex(hash)
        var waited = 0
        while (sm.find(sha) == null) {
            if (waited >= ADD_WAIT_MS) {
                uncertainAdds += hash
                runCatching { handle(hash).pause() }
                error("torrent add has not completed: $hash")
            }
            delay(ADD_POLL_MS)
            waited += ADD_POLL_MS.toInt()
        }
        return hash
    }

    override fun hashOf(magnet: String): String = AddTorrentParams.parseMagnetUri(magnet).infoHashes.key()

    override fun checkStorageError(hash: String) {
        storageErrors[hash]?.let { throw it }
    }

    override suspend fun awaitMetadata(hash: String): List<TorrentFile> {
        val done = metadata.getOrPut(hash) { CompletableDeferred() }
        // Poll too: the alert may be missed or fire before we subscribed.
        while (true) {
            storageErrors[hash]?.let { throw it }
            val ti = runCatching { handle(hash).torrentFile() }.getOrNull()
            if (ti != null && ti.isValid) break
            if (withTimeoutOrNull(POLL_MS) { done.await() } != null) break
        }
        val h = handle(hash)
        ignoreAll(h)
        val fs = h.torrentFile().files()
        return (0 until fs.numFiles()).map { TorrentFile(it, fs.filePath(it), fs.fileSize(it)) }
    }

    override fun fileSpan(hash: String, fileIndex: Int): FileSpan {
        val ti = handle(hash).torrentFile()
        val fs = ti.files()
        return FileSpan(fs.fileOffset(fileIndex), fs.fileSize(fileIndex), ti.pieceLength())
    }

    override fun selectFile(hash: String, fileIndex: Int) {
        val h = handle(hash)
        val n = h.torrentFile().numFiles()
        h.prioritizeFiles(Array(n) { if (it == fileIndex) Priority.DEFAULT else Priority.IGNORE })
        h.unsetFlags(TorrentFlags.UPLOAD_MODE)
    }

    override fun havePiece(hash: String, piece: Int): Boolean = handle(hash).havePiece(piece)

    override fun prioritize(hash: String, pieces: IntRange, deadlineMs: Int) {
        val h = handle(hash)
        for (p in pieces) {
            if (h.havePiece(p)) continue
            h.piecePriority(p, Priority.TOP_PRIORITY)
            h.setPieceDeadline(p, deadlineMs)
        }
    }

    override fun clearDeadlines(hash: String) {
        runCatching { handle(hash).clearPieceDeadlines() }
    }

    override fun status(hash: String): EngineStatus {
        storageErrors[hash]?.let { throw it }
        val st = handle(hash).status()
        return EngineStatus(st.downloadRate().toLong(), st.numPeers(), st.progress())
    }

    override fun filePath(hash: String, fileIndex: Int): File {
        val h = handle(hash)
        return File(h.savePath(), h.torrentFile().files().filePath(fileIndex))
    }

    override suspend fun removeAndAwait(hash: String, deleteFiles: Boolean) {
        val h = runCatching { handle(hash) }.getOrNull()
        check(h != null || hash !in uncertainAdds) { "Torrent add is still unresolved" }
        if (h != null) {
            val diskClosed = paused.getOrPut(hash) { CompletableDeferred() }
            h.unsetFlags(TorrentFlags.AUTO_MANAGED)
            h.pause()
            // torrent_removed is earlier than disk quiescence; torrent_paused closes all files.
            // https://libtorrent.org/reference-Alerts.html#torrent-paused-alert
            check(withTimeoutOrNull(REMOVE_WAIT_MS) { diskClosed.await(); true } == true) { "Torrent files are still in use" }
        }
        remove(hash, deleteFiles)
        val pending = removing[hash] ?: return
        check(withTimeoutOrNull(REMOVE_WAIT_MS) { pending.done.await(); true } == true) { "Torrent is still stopping" }
        removing.remove(hash, pending)
        paused.remove(hash)
        storageErrors.remove(hash)
        uncertainAdds.remove(hash)
    }

    override fun remove(hash: String, deleteFiles: Boolean) {
        metadata.remove(hash)
        val h = runCatching { handle(hash) }.getOrNull() ?: return
        h.pause()
        removing[hash] = PendingRemoval(deleteFiles)
        if (deleteFiles) sm.remove(h, SessionHandle.DELETE_FILES) else sm.remove(h)
    }
}
