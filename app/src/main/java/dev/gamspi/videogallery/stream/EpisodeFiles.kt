package dev.gamspi.videogallery.stream

import kotlinx.serialization.Serializable

@Serializable
data class EpisodeRef(val season: Int, val episode: Int) : Comparable<EpisodeRef> {
    override fun compareTo(other: EpisodeRef): Int =
        compareValuesBy(this, other, EpisodeRef::season, EpisodeRef::episode)
}

@Serializable
data class EpisodeFile(val ref: EpisodeRef, val fileIndex: Int)

private const val MIN_EPISODE_BYTES = 50L * 1024 * 1024

private val CI = RegexOption.IGNORE_CASE
private val SEASON_EPISODE = listOf(
    Regex("(?<![A-Za-z])S(\\d{1,2})[ ._-]?E(\\d{1,3})(?!\\d)", CI),
    Regex("(?<![A-Za-z\\d])(\\d{1,2})x(\\d{2,3})(?!\\d)", CI),
)
private val EPISODE_ONLY = listOf(
    Regex("(?<![A-Za-z])E(\\d{1,3})(?!\\d)", CI),
    Regex("(?:\u0441\u0435\u0440\u0438\u044f|\u0441\u0435\u0440\u0438\u0438|episode)[ ._]*(\\d{1,3})(?!\\d)", CI),
)
private val FOLDER_SEASON = listOf(
    Regex("(?:season|\u0441\u0435\u0437\u043e\u043d)[ ._]*(\\d{1,2})(?!\\d)", CI),
    Regex("(\\d{1,2})[ ._]*\u0441\u0435\u0437\u043e\u043d", CI),
    Regex("(?<![A-Za-z])S(\\d{1,2})(?!\\d)", CI),
)

/** Maps video files to episodes; files without an episode number are skipped. */
fun episodeFiles(videos: List<TorrentFile>, defaultSeason: Int): List<EpisodeFile> {
    val best = HashMap<EpisodeRef, TorrentFile>()
    for (file in videoFiles(videos)) {
        if (file.size < MIN_EPISODE_BYTES) continue
        val parts = file.path.replace('\\', '/').split('/')
        val name = parts.last()
        if (name.contains("sample", ignoreCase = true)) continue
        val ref = refOf(name, parts.dropLast(1), defaultSeason) ?: continue
        val prev = best[ref]
        if (prev == null || file.size > prev.size) best[ref] = file
    }
    return best.map { (ref, f) -> EpisodeFile(ref, f.index) }.sortedBy { it.ref }
}

private fun refOf(name: String, folders: List<String>, defaultSeason: Int): EpisodeRef? {
    for (re in SEASON_EPISODE) {
        val m = re.find(name) ?: continue
        return EpisodeRef(m.groupValues[1].toInt(), m.groupValues[2].toInt())
    }
    for (re in EPISODE_ONLY) {
        val m = re.find(name) ?: continue
        return EpisodeRef(folderSeason(folders) ?: defaultSeason, m.groupValues[1].toInt())
    }
    return null
}

private fun folderSeason(folders: List<String>): Int? {
    for (folder in folders.asReversed()) {
        for (re in FOLDER_SEASON) {
            re.find(folder)?.let { return it.groupValues[1].toInt() }
        }
    }
    return null
}
