package dev.gamspi.videogallery.stream

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.AlertListener
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
import org.libtorrent4j.swig.torrent_flags_t

private const val UPLOAD_LIMIT_BYTES = 1024 * 1024

/** Движок на libtorrent4j. Сессия стартует лениво при первом [add]. */
class Libtorrent4jEngine : TorrentEngine {
    private val sm = SessionManager(false)
    private val startLock = Any()
    private val metadata = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val pieces = MutableSharedFlow<Pair<String, Int>>(extraBufferCapacity = 256)

    override val pieceFinished: SharedFlow<Pair<String, Int>> = pieces.asSharedFlow()

    private val listener = object : AlertListener {
        override fun types(): IntArray = intArrayOf(
            AlertType.PIECE_FINISHED.swig(),
            AlertType.METADATA_RECEIVED.swig(),
        )

        override fun alert(alert: Alert<*>) {
            val hash = (alert as? TorrentAlert<*>)?.handle()?.infoHash()?.toHex()?.lowercase() ?: return
            when (alert) {
                is PieceFinishedAlert -> pieces.tryEmit(hash to alert.pieceIndex())
                is MetadataReceivedAlert -> metadata.getOrPut(hash) { CompletableDeferred() }.complete(Unit)
                else -> Unit
            }
        }
    }

    private fun ensureStarted() {
        synchronized(startLock) {
            if (sm.isRunning) return
            val settings = SettingsPack()
                .uploadRateLimit(UPLOAD_LIMIT_BYTES)
            settings.setEnableDht(true)
            settings.setEnableLsd(true)
            sm.addListener(listener)
            sm.start(SessionParams(settings))
        }
    }

    private fun handle(hash: String): TorrentHandle =
        sm.find(Sha1Hash.parseHex(hash)) ?: error("torrent not found: $hash")

    override suspend fun add(magnet: String, saveDir: File): String {
        ensureStarted()
        val hash = AddTorrentParams.parseMagnetUri(magnet).infoHashes.best.toHex().lowercase()
        val dir = File(saveDir, hash).also { it.mkdirs() }
        metadata.getOrPut(hash) { CompletableDeferred() }
        sm.download(magnet, dir, torrent_flags_t())
        return hash
    }

    override suspend fun awaitMetadata(hash: String): List<TorrentFile> {
        val done = metadata.getOrPut(hash) { CompletableDeferred() }
        // The handle may already hold metadata (alert fired before we subscribed).
        if (!done.isCompleted && runCatching { handle(hash).torrentFile() }.getOrNull() != null) {
            done.complete(Unit)
        }
        done.await()
        val fs = handle(hash).torrentFile().files()
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
        if (deleteFiles) sm.remove(h, SessionHandle.DELETE_FILES) else sm.remove(h)
    }
}
