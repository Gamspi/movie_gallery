package dev.gamspi.videogallery.stream

import java.io.File
import kotlinx.coroutines.flow.MutableSharedFlow

class FakeTorrentEngine : TorrentEngine {
    var files: List<TorrentFile> = emptyList()
    val have = mutableSetOf<Int>()
    val removed = mutableListOf<Pair<String, Boolean>>()
    val added = mutableListOf<String>()
    override val pieceFinished = MutableSharedFlow<Pair<String, Int>>(extraBufferCapacity = 64)

    override suspend fun add(magnet: String, saveDir: File): String {
        added += magnet
        return magnet.removePrefix("magnet:").lowercase()
    }

    override suspend fun awaitMetadata(hash: String): List<TorrentFile> = files

    override fun fileSpan(hash: String, fileIndex: Int) =
        FileSpan(0, files.first { it.index == fileIndex }.size, 1024 * 1024)

    override fun selectFile(hash: String, fileIndex: Int) {}
    override fun havePiece(hash: String, piece: Int) = piece in have
    override fun prioritize(hash: String, pieces: IntRange, deadlineMs: Int) {}
    override fun status(hash: String) = EngineStatus(2_202_010, 3, 0.5f)
    override fun filePath(hash: String, fileIndex: Int) = File("unused")
    override fun remove(hash: String, deleteFiles: Boolean) {
        removed += hash to deleteFiles
    }
}
