package dev.gamspi.videogallery.stream

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private const val BLOCK = 256 * 1024

class StreamServer(private val scope: CoroutineScope) {
    private var serverSocket: ServerSocket? = null
    private var serverScope: CoroutineScope? = null

    @Synchronized
    fun start(source: PieceSource): String {
        stop()
        val ss = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val sc = CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.IO)
        serverSocket = ss
        serverScope = sc
        sc.launch {
            while (true) {
                val socket = try {
                    ss.accept()
                } catch (_: IOException) {
                    break
                }
                launch { serve(socket, source) }
            }
        }
        return "http://127.0.0.1:${ss.localPort}/stream"
    }

    @Synchronized
    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        serverScope?.cancel()
        serverScope = null
    }

    private suspend fun serve(socket: Socket, source: PieceSource) {
        try {
            socket.use {
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                val req = readRequest(input) ?: return
                handle(socket, input, output, req, source)
            }
        } catch (_: IOException) {
            // client gone
        }
    }

    private class Request(val method: String, val path: String, val range: String?)

    private fun readRequest(input: InputStream): Request? {
        val first = readLine(input) ?: return null
        val parts = first.split(' ')
        if (parts.size < 2) return null
        var range: String? = null
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0 && line.substring(0, idx).trim().equals("Range", ignoreCase = true)) {
                range = line.substring(idx + 1).trim()
            }
        }
        return Request(parts[0].uppercase(), parts[1], range)
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return null
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
            if (sb.length > 8192) return null
        }
        return sb.toString()
    }

    /** Parsed inclusive range or null when unsatisfiable/invalid. */
    private fun parseRange(header: String, length: Long): LongRange? {
        val spec = header.trim().removePrefix("bytes=").takeIf { header.trim().startsWith("bytes=") } ?: return null
        val first = spec.substringBefore(',').trim()
        val dash = first.indexOf('-')
        if (dash < 0) return null
        val a = first.substring(0, dash).trim()
        val b = first.substring(dash + 1).trim()
        return if (a.isEmpty()) {
            val n = b.toLongOrNull() ?: return null
            if (n <= 0 || length == 0L) return null
            maxOf(0L, length - n)..(length - 1)
        } else {
            val start = a.toLongOrNull() ?: return null
            val end = if (b.isEmpty()) length - 1 else (b.toLongOrNull() ?: return null)
            if (start >= length || end < start) return null
            start..minOf(end, length - 1)
        }
    }

    private suspend fun handle(
        socket: Socket,
        input: InputStream,
        output: OutputStream,
        req: Request,
        source: PieceSource,
    ) {
        val path = req.path.substringBefore('?')
        if (path != "/stream") return respond(output, 404, "Not Found")
        if (req.method != "GET" && req.method != "HEAD") return respond(output, 405, "Method Not Allowed")

        val length = source.length
        val range = req.range
        val (status, reason, span) = if (range == null) {
            Triple(200, "OK", 0L until length)
        } else {
            val r = parseRange(range, length)
                ?: return respond(output, 416, "Range Not Satisfiable", "Content-Range: bytes */$length")
            Triple(206, "Partial Content", r)
        }
        val count = if (span.isEmpty()) 0L else span.last - span.first + 1
        val headers = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: ${contentTypeOf(source.fileName)}\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Content-Length: $count\r\n")
            if (status == 206) append("Content-Range: bytes ${span.first}-${span.last}/$length\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(headers.toByteArray(Charsets.ISO_8859_1))
        output.flush()
        if (req.method == "HEAD" || count == 0L) return

        // Writer runs as a child; a watcher blocks on the socket input and cancels the writer on
        // EOF/reset. This detects a client that left while we only wait in awaitRange (no write yet).
        coroutineScope {
            val writer = launch {
                var pos = span.first
                val buf = ByteArray(BLOCK)
                while (pos <= span.last) {
                    ensureActive()
                    val end = minOf(pos + BLOCK - 1, span.last)
                    source.awaitRange(pos, end)
                    val want = (end - pos + 1).toInt()
                    val n = source.read(pos, buf, 0, want)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                    pos += n
                }
                output.flush()
            }
            val watcher = launch(Dispatchers.IO) {
                try {
                    while (input.read() >= 0) { /* ignore extra bytes */ }
                } catch (_: IOException) {
                }
                writer.cancel()
            }
            try {
                writer.join()
            } finally {
                // unblock the watcher's blocking read, then let it finish
                runCatching { socket.close() }
                watcher.cancel()
            }
        }
    }

    private fun respond(output: OutputStream, code: Int, reason: String, extra: String? = null) {
        val h = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            if (extra != null) append("$extra\r\n")
            append("Content-Length: 0\r\nConnection: close\r\n\r\n")
        }
        output.write(h.toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }
}
