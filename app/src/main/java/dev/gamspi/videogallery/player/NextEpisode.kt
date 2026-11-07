package dev.gamspi.videogallery.player

import dev.gamspi.videogallery.stream.EpisodeFile
import dev.gamspi.videogallery.stream.EpisodeRef

/** What to do after an episode ends. */
sealed interface NextStep {
    /** The next episode is a file of the current release. */
    data class PlayFile(val ref: EpisodeRef) : NextStep

    /** The next episode needs another release. */
    data class PickRelease(val ref: EpisodeRef) : NextStep

    data object SeriesDone : NextStep
}

fun afterEpisode(current: EpisodeRef, files: List<EpisodeFile>, seasonEpisodes: Int?, seasonCount: Int?): NextStep {
    fun has(ref: EpisodeRef) = files.any { it.ref == ref }
    val sameSeason = EpisodeRef(current.season, current.episode + 1)
    if (has(sameSeason)) return NextStep.PlayFile(sameSeason)
    val nextSeason = EpisodeRef(current.season + 1, 1)
    val seasonOver = seasonEpisodes != null && current.episode >= seasonEpisodes
    return when {
        seasonOver && has(nextSeason) -> NextStep.PlayFile(nextSeason)
        seasonOver && seasonCount != null && current.season < seasonCount -> NextStep.PickRelease(nextSeason)
        seasonEpisodes != null && !seasonOver -> NextStep.PickRelease(sameSeason)
        else -> NextStep.SeriesDone
    }
}

/** «S02E05» caption of an episode. */
fun episodeCaption(season: Int, episode: Int): String = "S%02dE%02d".format(season, episode)
