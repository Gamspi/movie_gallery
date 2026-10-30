package dev.gamspi.videogallery.stream

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
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
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.MetadataReceivedAlert
import org.libtorrent4j.alerts.PieceFinishedAlert
import org.libtorrent4j.alerts.TorrentAlert
import org.libtorrent4j.alerts.TorrentDeleteFailedAlert
import org.libtorrent4j.alerts.TorrentDeletedAlert
import org.libtorrent4j.alerts.TorrentRemovedAlert
import org.libtorrent4j.swig.alert_category_t
import org.libtorrent4j.swig.settings_pack
import org.libtorrent4j.swig.torrent_flags_t

private const val UPLOAD_LIMIT_BYTES = 1024 * 1024
private const val REMOVE_WAIT_MS = 5_000L
private const val POLL_MS = 1_000L

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
    private val pieces = MutableSharedFlow<Pair<String, Int>>(extraBufferCapacity = 1024)

    override val pieceFinished: SharedFlow<Pair<String, Int>> = pieces.asSharedFlow()

    private val listener = object : AlertListener {
        override fun types(): IntArray = intArrayOf(
            AlertType.PIECE_FINISHED.swig(),
            AlertType.METADATA_RECEIVED.swig(),
            AlertType.TORRENT_REMOVED.swig(),
            AlertType.TORRENT_DELETED.swig(),
            AlertType.TORRENT_DELETE_FAILED.swig(),
        )

        override fun alert(alert: Alert<*>) {
            when (alert) {
                is PieceFinishedAlert -> pieces.tryEmit(alert.handle().infoHash().toHex().lowercase() to alert.pieceIndex())
                is MetadataReceivedAlert -> {
                    val hash = alert.handle().infoHash().toHex().lowercase()
                    runCatching { ignoreAll(alert.handle()) }
                    metadata.getOrPut(hash) { CompletableDeferred() }.complete(Unit)
                }
                is TorrentRemovedAlert -> finishRemoval(alert.infoHashes.key(), deleted = false)
                is TorrentDeletedAlert -> finishRemoval(alert.infoHashes.key(), deleted = true)
                is TorrentDeleteFailedAlert -> finishRemoval(alert.infoHashes.key(), deleted = true)
                else -> Unit
            }
        }
    }

    private fun finishRemoval(hash: String, deleted: Boolean) {
        val p = removing[hash] ?: return
        if (p.deleteFiles == deleted) {
            removing.remove(hash, p)
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
            settings.setInteger(settings_pack.int_types.alert_mask.swigValue(), alert_category_t.all().to_int())
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
        val hash = AddTorrentParams.parseMagnetUri(magnet).infoHashes.key()
        // A previous session of the same torrent may still be shutting down.
        removing[hash]?.let { withTimeoutOrNull(REMOVE_WAIT_MS) { it.done.await() } }
        val dir = File(saveDir, hash).also { it.mkdirs() }
        metadata.getOrPut(hash) { CompletableDeferred() }
        sm.download(magnet, dir, torrent_flags_t())
        return hash
    }

    override suspend fun awaitMetadata(hash: String): List<TorrentFile> {
        val done = metadata.getOrPut(hash) { CompletableDeferred() }
        // Poll too: the alert may be missed or fire before we subscribed.
        while (true) {
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
        val st = handle(hash).status()
        return EngineStatus(st.downloadRate().toLong(), st.numPeers(), st.progress())
    }

    override fun filePath(hash: String, fileIndex: Int): File {
        val h = handle(hash)
        return File(h.savePath(), h.torrentFile().files().filePath(fileIndex))
    }

    override fun remove(hash: String, deleteFiles: Boolean) {
        metadata.remove(hash)
        val h = runCatching { handle(hash) }.getOrNull() ?: return
        removing[hash] = PendingRemoval(deleteFiles)
        if (deleteFiles) sm.remove(h, SessionHandle.DELETE_FILES) else sm.remove(h)
    }
}
