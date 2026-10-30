package dev.gamspi.videogallery.stream

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val READINESS_DEADLINE_STEP_MS = 100
private const val RANGE_DEADLINE_MS = 500
private const val RANGE_DEADLINE_STEP_MS = 100
private const val READ_AHEAD_BYTES = 16L * 1024 * 1024
private const val HAVE_POLL_MS = 1_000L

class StreamManager(
    private val engine: TorrentEngine,
    private val server: StreamServer,
    private val filesDir: File,
    private val scope: CoroutineScope,
    private val metadataTimeoutMs: Long = 60_000,
    private val tickMs: Long = 1_000,
) {
    private class Session(val id: Long, val title: String) {
        private var hashValue: String? = null
        @Volatile var job: Job? = null
        @Volatile var files: List<TorrentFile> = emptyList()
        @Volatile var source: Closeable? = null

        val hash: String? @Synchronized get() = hashValue

        @Synchronized fun setHash(h: String) {
            hashValue = h
        }

        /** Atomically takes the hash so the torrent is removed exactly once. */
        @Synchronized fun takeHash(): String? = hashValue.also { hashValue = null }
    }

    private val _state = MutableStateFlow<StreamState>(StreamState.Idle)
    val state: StateFlow<StreamState> = _state.asStateFlow()

    private val _statusLine = MutableStateFlow<String?>(null)
    val statusLine: StateFlow<String?> = _statusLine.asStateFlow()

    private val lock = Any()
    private var session: Session? = null
    private var counter = 0L

    /** Id of the latest started session (0 before any start). */
    fun currentSessionId(): Long = synchronized(lock) { counter }

    /** Stops the session only if [id] is still the current one. */
    fun stopIf(id: Long) {
        val current = synchronized(lock) { session?.id == id }
        if (current) stop()
    }

    fun start(magnet: String, title: String) {
        stop()
        val s = synchronized(lock) { Session(++counter, title).also { session = it } }
        _state.value = StreamState.FetchingMetadata
        s.job = scope.launch {
            try {
                guarded(s) {
                    // add() must not be cancelled half-way, or the torrent would be lost.
                    val hash = withContext(NonCancellable) {
                        engine.add(magnet, File(filesDir, "torrents")).also { s.setHash(it) }
                    }
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
                        else -> if (isCurrent(s)) _state.value = StreamState.ChooseFile(videos)
                    }
                }
            } finally {
                if (!isCurrent(s)) withContext(NonCancellable) { cleanup(s) }
            }
        }
    }

    fun chooseFile(index: Int) {
        val s = synchronized(lock) { session } ?: return
        val hash = s.hash ?: return
        val cur = _state.value
        if (cur !is StreamState.ChooseFile || s.files.none { it.index == index }) return
        // Claim the choice synchronously: a second call sees Buffering and returns.
        if (!_state.compareAndSet(cur, StreamState.Buffering(0, ""))) return
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
        runCatching { s.source?.close() }
        s.source = null
        val hash = s.takeHash() ?: return
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
            if (!ready) {
                val percent = readinessPercent(needed) { engine.havePiece(hash, it) }
                val st = engine.status(hash)
                _statusLine.value = statusLine(st.downloadRate, st.peers, overallPercent(st))
                if (percent >= 100) {
                    val src = EnginePieceSource(engine, hash, span, engine.filePath(hash, index))
                    s.source = src
                    val url = server.start(src)
                    ready = true
                    _state.value = StreamState.Ready(url, "bt:$hash:$index", s.title)
                } else {
                    _state.value = StreamState.Buffering(percent, statusLine(st.downloadRate, st.peers, null))
                }
            } else {
                // A one-off failure must not tear down a playing session.
                runCatching {
                    val st = engine.status(hash)
                    _statusLine.value = statusLine(st.downloadRate, st.peers, overallPercent(st))
                }
            }
            delay(tickMs)
        }
    }

    private fun overallPercent(st: EngineStatus) = (st.progress * 100).toInt().coerceIn(0, 100)
}

/** Reads the file from disk; waits for pieces via the engine. One shared file handle. */
internal class EnginePieceSource(
    private val engine: TorrentEngine,
    private val hash: String,
    private val span: FileSpan,
    private val file: File,
    private val readAheadBytes: Long = READ_AHEAD_BYTES,
) : PieceSource, Closeable {
    override val length: Long = span.length
    override val fileName: String = file.name

    private val deadlined = HashSet<Int>()
    private var lastStart = -1L
    private var lastEnd = -1L
    private var raf: RandomAccessFile? = null
    private var closed = false

    private fun setDeadlines(start: Long, endInclusive: Long) = synchronized(deadlined) {
        val seek = lastStart >= 0 && (start < lastStart || start > lastEnd + readAheadBytes)
        if (seek) {
            engine.clearDeadlines(hash)
            deadlined.clear()
        }
        lastStart = start
        lastEnd = endInclusive
        val ahead = minOf(span.length - 1, endInclusive + readAheadBytes).coerceAtLeast(endInclusive)
        span.piecesFor(start, ahead).forEachIndexed { i, piece ->
            if (deadlined.add(piece)) {
                engine.prioritize(hash, piece..piece, RANGE_DEADLINE_MS + i * RANGE_DEADLINE_STEP_MS)
            }
        }
    }

    override suspend fun awaitRange(start: Long, endInclusive: Long) {
        setDeadlines(start, endInclusive)
        for (piece in span.piecesFor(start, endInclusive)) {
            // Subscribe first, then check; the timeout re-checks in case an event was dropped.
            var first = true
            while (!engine.havePiece(hash, piece)) {
                // Another connection's seek may have cleared our deadline: re-apply after each timeout.
                if (!first) engine.prioritize(hash, piece..piece, RANGE_DEADLINE_MS)
                first = false
                withTimeoutOrNull(HAVE_POLL_MS) {
                    engine.pieceFinished
                        .onSubscription { if (engine.havePiece(hash, piece)) emit(hash to piece) }
                        .first { it.first == hash && it.second == piece }
                }
            }
        }
    }

    @Synchronized
    override fun read(position: Long, buffer: ByteArray, offset: Int, count: Int): Int {
        if (closed) return -1
        val f = raf ?: RandomAccessFile(file, "r").also { raf = it }
        f.seek(position)
        return f.read(buffer, offset, count)
    }

    @Synchronized
    override fun close() {
        closed = true
        runCatching { raf?.close() }
        raf = null
    }
}
