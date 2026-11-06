package dev.gamspi.videogallery.torrents.rutracker

import dev.gamspi.videogallery.torrents.ProviderId
import dev.gamspi.videogallery.torrents.TorrentResult
import dev.gamspi.videogallery.torrents.parseQuality
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** One row of tracker.php before film filtering. */
data class RutrackerRow(val result: TorrentResult, val forum: String?)

/** Magnet and hash taken from a topic page. */
data class RutrackerMagnet(val infoHash: String, val magnet: String)

object RutrackerParser {
    private val topicIdRe = Regex("""[?&]t=(\d+)""")
    private val hashRe = Regex("""btih:([0-9a-fA-F]{40}|[A-Za-z2-7]{32})(?![0-9A-Za-z])""")
    private val sizeRe = Regex("""([0-9]+(?:[.,][0-9]+)?)\s*([A-Za-zА-Яа-я]+)""")
    private val loginFieldRe = Regex("""name\s*=\s*["']?login_username""")
    private val intRe = Regex("""-?\d+""")
    private val filmForumRe = Regex("""кино|фильм|hd video|uhd video|dvd|3d""", RegexOption.IGNORE_CASE)
    private val excludedForumRe =
        Regex("""сериал|музык|(?<![а-яё])игр|трейлер|клип|концерт""", RegexOption.IGNORE_CASE)

    /** True for a page that shows the login form instead of member content. */
    fun isLoginPage(html: String): Boolean =
        loginFieldRe.containsMatchIn(html) && !html.contains("logged-in-username")

    /** Search rows; [baseUrl] builds absolute topic links. Rows without a topic id are dropped. */
    fun parseSearch(html: String, baseUrl: String): List<RutrackerRow> =
        Jsoup.parse(html).select("tr:has(a.tLink)").mapNotNull { parseRow(it, baseUrl) }

    /** Forum filter: film sections only; an unknown forum is kept. */
    fun isFilmForum(forum: String?): Boolean =
        forum.isNullOrBlank() || (filmForumRe.containsMatchIn(forum) && !excludedForumRe.containsMatchIn(forum))

    fun parseMagnet(html: String): RutrackerMagnet? {
        val doc = Jsoup.parse(html)
        val links = doc.select("a.magnet-link[href^=magnet:]") + doc.select("a[href^=magnet:]")
        for (a in links) {
            val href = a.attr("href").trim()
            val hash = hashRe.find(href)?.groupValues?.get(1) ?: continue
            return RutrackerMagnet(hash.lowercase(), href)
        }
        return null
    }

    private fun digitsOrNull(s: String): String? = s.trim().takeIf { it.isNotEmpty() && it.all(Char::isDigit) }

    private fun parseRow(row: Element, baseUrl: String): RutrackerRow? {
        val link = row.selectFirst("a.tLink") ?: return null
        val id = digitsOrNull(link.attr("data-topic_id"))
            ?: topicIdRe.find(link.attr("href"))?.groupValues?.get(1)
            ?: digitsOrNull(row.attr("data-topic_id"))
            ?: return null
        val title = link.text().trim()
        if (title.isEmpty()) return null
        val forum = row.selectFirst("td.f-name-col a, .f-name a, a.f")?.text()?.trim()?.ifEmpty { null }
        val topicUrl = RutrackerSession.forumUrl(baseUrl, "viewtopic.php").newBuilder()
            .addQueryParameter("t", id).build().toString()
        return RutrackerRow(
            TorrentResult(
                title = title,
                provider = ProviderId.Rutracker,
                sizeBytes = size(row),
                seeders = number(row.selectFirst("b.seedmed, .seedmed, td.seed b, td.seed")),
                leechers = number(row.selectFirst("td.leechmed, .leechmed, td.leech b, td.leech")),
                quality = parseQuality(title),
                infoHash = "",
                magnet = "",
                topicUrl = topicUrl,
            ),
            forum,
        )
    }

    private fun size(row: Element): Long {
        val cell = row.selectFirst("td.tor-size") ?: return 0L
        cell.attr("data-ts_text").trim().toLongOrNull()?.takeIf { it > 0 }?.let { return it }
        return parseSize(cell.text().replace(Char(160), ' '))
    }

    // Rutracker marks long-dead seeds with negative values; clamp to zero.
    private fun number(e: Element?): Int =
        e?.text()?.let { intRe.find(it)?.value?.toIntOrNull() }?.coerceAtLeast(0) ?: 0

    private fun parseSize(text: String): Long {
        val m = sizeRe.find(text) ?: return 0L
        val value = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return 0L
        val mult = when (m.groupValues[2].uppercase()) {
            "B", "Б" -> 1L
            "KB", "КБ" -> 1L shl 10
            "MB", "МБ" -> 1L shl 20
            "GB", "ГБ" -> 1L shl 30
            "TB", "ТБ" -> 1L shl 40
            else -> return 0L
        }
        return (value * mult).toLong()
    }
}
