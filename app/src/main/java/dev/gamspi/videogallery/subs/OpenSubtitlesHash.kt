package dev.gamspi.videogallery.subs

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import okhttp3.OkHttpClient
import okhttp3.Request

private const val CHUNK = 65536

/** Classic OSDb hash: size + sum of LE uint64 words of head and tail chunks, mod 2^64. */
fun openSubtitlesHash(size: Long, head: ByteArray, tail: ByteArray): String {
    var hash = size
    hash += sumWords(head)
    hash += sumWords(tail)
    return "%016x".format(hash)
}

private fun sumWords(bytes: ByteArray): Long {
    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    var sum = 0L
    while (buf.remaining() >= 8) sum += buf.long
    return sum
}

/** Hash of a remote file via two Range requests; null if Range is unsupported or the request fails. */
suspend fun fetchHashOverHttp(client: OkHttpClient, url: String): String? {
    try {
        val (size, head) = rangeGet(client, url, "bytes=0-${CHUNK - 1}") ?: return null
        if (size == null) return null
        val tail = if (size <= CHUNK) {
            head
        } else {
            rangeGet(client, url, "bytes=${size - CHUNK}-${size - 1}")?.second
                ?: return null
        }
        return openSubtitlesHash(size, head, tail)
    } catch (e: IOException) {
        return null
    }
}

/** Returns total size (from Content-Range) and body for a 206 answer, else null. */
private suspend fun rangeGet(client: OkHttpClient, url: String, range: String): Pair<Long?, ByteArray>? {
    val request = Request.Builder().url(url).header("Range", range).build()
    return client.newCall(request).awaitBody { response ->
        if (response.code != 206) return@awaitBody null
        val total = response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
        val body = response.body.bytes()
        total to body
    }
}
