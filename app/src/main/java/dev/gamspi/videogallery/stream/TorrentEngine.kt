package dev.gamspi.videogallery.stream

import java.io.File
import kotlinx.coroutines.flow.SharedFlow

data class EngineStatus(val downloadRate: Long, val peers: Int, val progress: Float)

interface TorrentEngine {
    /** Добавляет раздачу; файлы кладёт в `saveDir/<infoHash>/`. Возвращает infoHash в нижнем регистре. */
    suspend fun add(magnet: String, saveDir: File): String

    suspend fun awaitMetadata(hash: String): List<TorrentFile>

    fun fileSpan(hash: String, fileIndex: Int): FileSpan

    /** Выбранному файлу — обычный приоритет, остальным — 0. */
    fun selectFile(hash: String, fileIndex: Int)

    fun havePiece(hash: String, piece: Int): Boolean

    fun prioritize(hash: String, pieces: IntRange, deadlineMs: Int)

    /** (infoHash, pieceIndex). */
    val pieceFinished: SharedFlow<Pair<String, Int>>

    fun status(hash: String): EngineStatus

    fun filePath(hash: String, fileIndex: Int): File

    fun remove(hash: String, deleteFiles: Boolean)

    /** Сбрасывает все дедлайны кусков (перемотка). */
    fun clearDeadlines(hash: String) {}
}

sealed interface StreamState {
    data object Idle : StreamState
    data object FetchingMetadata : StreamState
    data class ChooseFile(val files: List<TorrentFile>) : StreamState
    data class Buffering(val percent: Int, val status: String) : StreamState
    data class Ready(val url: String, val key: String, val title: String) : StreamState
    data class Failed(val message: String) : StreamState
}
