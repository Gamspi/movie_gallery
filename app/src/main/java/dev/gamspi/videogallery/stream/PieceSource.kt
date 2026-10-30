package dev.gamspi.videogallery.stream

interface PieceSource {
    val length: Long
    val fileName: String

    /** Raises priority (deadlines) for the range and suspends until bytes are on disk. Cancellable. */
    suspend fun awaitRange(start: Long, endInclusive: Long)

    fun read(position: Long, buffer: ByteArray, offset: Int, count: Int): Int
}
