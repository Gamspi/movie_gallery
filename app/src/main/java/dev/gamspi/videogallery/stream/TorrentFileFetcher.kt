package dev.gamspi.videogallery.stream

import dev.gamspi.videogallery.torrents.await
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer

internal const val TORRENT_FETCH_TIMEOUT_MS = 10_000L
internal const val TORRENT_FILE_MAX_BYTES = 5L * 1024 * 1024
internal const val TORRENT_MAX_REDIRECTS = 5

fun interface TorrentFileSource {
    suspend fun fetch(url: String): ByteArray?
}

/** Fetches bounded HTTP(S) metadata; failures return null, cancellation propagates. */
class TorrentFileFetcher(
    client: OkHttpClient,
    private val timeoutMs: Long = TORRENT_FETCH_TIMEOUT_MS,
    private val maxBytes: Long = TORRENT_FILE_MAX_BYTES,
    private val maxRedirects: Int = TORRENT_MAX_REDIRECTS,
) : TorrentFileSource {
    init {
        require(timeoutMs > 0)
        require(maxBytes in 0 until Int.MAX_VALUE.toLong())
        require(maxRedirects >= 0)
    }

    private val http = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .build()

    @OptIn(InternalCoroutinesApi::class)
    override suspend fun fetch(url: String): ByteArray? = withContext(Dispatchers.IO) {
        // Keep one deadline across the entire redirect chain.
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        try {
            var current: HttpUrl = url.toHttpUrlOrNull() ?: return@withContext null
            for (hop in 0..maxRedirects) {
                ensureActive()
                val remainingTime = deadline - System.nanoTime()
                if (remainingTime <= 0) return@withContext null
                val call = http.newCall(Request.Builder().url(current).build())
                call.timeout().timeout(remainingTime, TimeUnit.NANOSECONDS)
                // Run on cancellation, without waiting for a blocked body read to finish.
                val cancellation = currentCoroutineContext().job.invokeOnCompletion(
                    onCancelling = true,
                    invokeImmediately = true,
                ) { cause ->
                    if (cause != null) call.cancel()
                }
                try {
                    call.await().use { response ->
                        if (response.code in 300..399) {
                            if (hop == maxRedirects) return@withContext null
                            val location = response.header("Location") ?: return@withContext null
                            current = current.resolve(location) ?: return@withContext null
                        } else if (response.isSuccessful) {
                            val body = response.body
                            if (body.contentLength() > maxBytes) return@withContext null
                            val source = body.source()
                            val buffer = Buffer()
                            while (buffer.size <= maxBytes) {
                                ensureActive()
                                val remaining = maxBytes + 1 - buffer.size
                                if (source.read(buffer, minOf(8192L, remaining)) == -1L) {
                                    return@withContext buffer.readByteArray()
                                }
                            }
                            return@withContext null
                        } else {
                            return@withContext null
                        }
                    }
                } finally {
                    cancellation.dispose()
                }
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Socket cancellation can surface as IOException during a body read.
            currentCoroutineContext().ensureActive()
            null
        }
    }
}
