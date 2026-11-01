package dev.gamspi.videogallery.settings

data class TorrentStoragePrefs(
    val keepAfterPlayback: Boolean = false,
    val limitGiB: Int = 10,
) {
    val limitBytes: Long get() = limitGiB.coerceIn(1, 100) * 1024L * 1024 * 1024

    fun normalized(): TorrentStoragePrefs = copy(limitGiB = limitGiB.coerceIn(1, 100))
}
