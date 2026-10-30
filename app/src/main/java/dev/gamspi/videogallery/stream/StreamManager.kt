package dev.gamspi.videogallery.stream

import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val READINESS_DEADLINE_STEP_MS = 100
private const val RANGE_DEADLINE_MS = 500

class StreamManager(
    private val engine: TorrentEngine,
    private val server: StreamServer,
    private val filesDir: File,
    private val scope: CoroutineScope,
    private val metadataTimeoutMs: Long = 60_000,
    private val tickMs: Long = 1_000,
) {
    private class Session(val magnet: String, val title: String) {
        @Volatile var hash: String? = null
        @Volatile var job: Job? = null
        @Volatile var files: List<TorrentFile> = emptyList()
    }

    private val _state = MutableStateFlow<StreamState>(StreamState.Idle)
    val state: StateFlow<StreamState> = _state.asStateFlow()

    private val _statusLine = MutableStateFlow<String?>(null)
    val statusLine: StateFlow<String?> = _statusLine.asStateFlow()

    private val lock = Any()
    private var session: Session? = null

    fun start(magnet: String, title: String) {
        stop()
        val s = Session(magnet, title)
        synchronized(lock) { session = s }
        _state.value = StreamState.FetchingMetadata
        s.job = scope.launch {
            guarded(s) {
                val hash = engine.add(magnet, File(filesDir, "torrents"))
                s.hash = hash
                val all = withTimeoutOrNull(metadataTimeoutMs) { engine.awaitMetadata(hash) }
                if (all == null) {
                    fail(s, "Нет пиров — выберите другую раздачу")
                    return@guarded
                }
                val videos = videoFiles(all)
                s.files = videos
                when {
                    videos.isEmpty() -> fail(s, "Видеофайлы не найдены")
                    videos.size == 1 -> buffer(s, hash, videos[0].index)
                    else -> _state.value = StreamState.ChooseFile(videos)
                }
            }
        }
    }

    fun chooseFile(index: Int) {
        val s = synchronized(lock) { session } ?: return
        val hash = s.hash ?: return
        if (_state.value !is StreamState.ChooseFile || s.files.none { it.index == index }) return
        s.job = scope.launch { guarded(s) { buffer(s, hash, index) } }
    }

    fun stop() {
        val s = synchronized(lock) { session.also { session = null } }
        s?.job?.cancel()
        server.stop()
        if (s != null) cleanup(s)
        _statusLine.value = null
        _state.value = StreamState.Idle
    }

    private suspend fun guarded(s: Session, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            fail(s, "Не удалось запустить раздачу")
        }
    }

    private fun isCurrent(s: Session) = synchronized(lock) { session === s }

    private fun fail(s: Session, message: String) {
        if (!isCurrent(s)) return
        cleanup(s)
        _statusLine.value = null
        _state.value = StreamState.Failed(message)
    }

    private fun cleanup(s: Session) {
        val hash = s.hash ?: return
        s.hash = null
        runCatching { engine.remove(hash, true) }
        runCatching { File(filesDir, "torrents/$hash").deleteRecursively() }
    }

    private suspend fun buffer(s: Session, hash: String, index: Int) {
        engine.selectFile(hash, index)
        val span = engine.fileSpan(hash, index)
        val needed = span.readinessPieces()
        needed.sorted().forEachIndexed { i, piece ->
            engine.prioritize(hash, piece..piece, 1_000 + i * READINESS_DEADLINE_STEP_MS)
        }
        var ready = false
        while (true) {
            if (!isCurrent(s)) return
            val percent = readinessPercent(needed) { engine.havePiece(hash, it) }
            val st = engine.status(hash)
            val overall = (st.progress * 100).toInt().coerceIn(0, 100)
            _statusLine.value = statusLine(st.downloadRate, st.peers, overall)
            if (!ready) {
                if (percent >= 100) {
                    val file = engine.filePath(hash, index)
                    val url = server.start(DiskPieceSource(hash, span, file))
                    ready = true
                    _state.value = StreamState.Ready(url, "bt:$hash:$index", s.title)
                } else {
                    _state.value = StreamState.Buffering(percent, statusLine(st.downloadRate, st.peers, null))
                }
            }
            delay(tickMs)
        }
    }

    private inner class DiskPieceSource(
        private val hash: String,
        private val span: FileSpan,
        private val file: File,
    ) : PieceSource {
        override val length: Long = span.length
        override val fileName: String = file.name

        override suspend fun awaitRange(start: Long, endInclusive: Long) {
            val pieces = span.piecesFor(start, endInclusive)
            engine.prioritize(hash, pieces, RANGE_DEADLINE_MS)
            for (piece in pieces) {
                if (engine.havePiece(hash, piece)) continue
                // Subscribe first, then re-check: no window where the piece is lost.
                engine.pieceFinished
                    .onSubscription { if (engine.havePiece(hash, piece)) emit(hash to piece) }
                    .first { it.first == hash && it.second == piece }
            }
        }

        override fun read(position: Long, buffer: ByteArray, offset: Int, count: Int): Int =
            RandomAccessFile(file, "r").use {
                it.seek(position)
                it.read(buffer, offset, count)
            }
    }
}
