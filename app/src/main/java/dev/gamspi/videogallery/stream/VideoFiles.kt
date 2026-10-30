package dev.gamspi.videogallery.stream

data class TorrentFile(val index: Int, val path: String, val size: Long)

private val CONTENT_TYPES = mapOf(
    "mkv" to "video/x-matroska",
    "mp4" to "video/mp4",
    "m4v" to "video/mp4",
    "avi" to "video/x-msvideo",
    "mov" to "video/quicktime",
    "webm" to "video/webm",
    "ts" to "video/mp2t",
    "m2ts" to "video/mp2t",
    "wmv" to "video/x-ms-wmv",
    "mpg" to "video/mpeg",
    "mpeg" to "video/mpeg",
)

private fun extensionOf(path: String): String =
    path.replace('\\', '/').substringAfterLast('/').substringAfterLast('.', "").lowercase()

fun videoFiles(files: List<TorrentFile>): List<TorrentFile> =
    files.filter { extensionOf(it.path) in CONTENT_TYPES }.sortedByDescending { it.size }

fun contentTypeOf(path: String): String =
    CONTENT_TYPES[extensionOf(path)] ?: "application/octet-stream"
