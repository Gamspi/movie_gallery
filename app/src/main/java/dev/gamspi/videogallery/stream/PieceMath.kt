package dev.gamspi.videogallery.stream

private const val MIB = 1024L * 1024
private const val MAX_HEAD = 20 * MIB
private const val TAIL = 5 * MIB

/** Файл внутри торрента: [offset] — смещение файла в торренте. */
data class FileSpan(val offset: Long, val length: Long, val pieceLength: Int)

/** Индексы кусков торрента для байтового диапазона файла (включительно). */
fun FileSpan.piecesFor(start: Long, endInclusive: Long): IntRange {
    val first = ((offset + start) / pieceLength).toInt()
    val last = ((offset + endInclusive) / pieceLength).toInt()
    return first..last
}

/** Куски головы (min(5 %, 20 МБ)) и хвоста (min(5 МБ, длина)) файла. */
fun FileSpan.readinessPieces(): Set<Int> {
    if (length <= 0) return emptySet()
    val head = minOf(length / 20, MAX_HEAD).coerceAtLeast(1)
    val tail = minOf(TAIL, length)
    return (piecesFor(0, head - 1) + piecesFor(length - tail, length - 1)).toSet()
}

/** 0..100; пустое множество — 100. */
fun readinessPercent(needed: Set<Int>, have: (Int) -> Boolean): Int =
    if (needed.isEmpty()) 100 else needed.count(have) * 100 / needed.size
