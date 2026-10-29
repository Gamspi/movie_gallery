package dev.gamspi.videogallery.torrents.rutor

import dev.gamspi.videogallery.torrents.ProviderId
import dev.gamspi.videogallery.torrents.TorrentResult
import dev.gamspi.videogallery.torrents.parseQuality
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

object RutorParser {
    private val hashRe = Regex("""btih:([0-9a-zA-Z]+)""")
    private val sizeRe = Regex("""([0-9]+(?:\.[0-9]+)?)\s*([A-Za-z]+)""")
    private val digitsRe = Regex("""\d+""")

    fun parse(html: String): List<TorrentResult> =
        Jsoup.parse(html).select("tr.gai, tr.tum").mapNotNull(::parseRow)

    private fun parseRow(row: Element): TorrentResult? {
        val magnet = row.selectFirst("a[href^=magnet:]")?.attr("href") ?: return null
        val hash = hashRe.find(magnet)?.groupValues?.get(1)?.lowercase()
        if (hash.isNullOrBlank()) return null
        val title = row.selectFirst("a[href^=/torrent/]")?.text()?.trim().orEmpty()
        val cells = row.select("> td")
        val sizeText = cells.getOrNull(cells.size - 2)?.text()?.replace(Char(160), ' ').orEmpty()
        return TorrentResult(
            title = title,
            provider = ProviderId.Rutor,
            sizeBytes = parseSize(sizeText),
            seeders = count(row.selectFirst("span.green")),
            leechers = count(row.selectFirst("span.red")),
            quality = parseQuality(title),
            infoHash = hash,
            magnet = magnet,
        )
    }

    private fun count(e: Element?): Int =
        e?.text()?.let { digitsRe.find(it)?.value?.toIntOrNull() } ?: 0

    private fun parseSize(text: String): Long {
        val m = sizeRe.find(text) ?: return 0L
        val value = m.groupValues[1].toDoubleOrNull() ?: return 0L
        val mult = when (m.groupValues[2].uppercase()) {
            "B" -> 1L
            "KB" -> 1L shl 10
            "MB" -> 1L shl 20
            "GB" -> 1L shl 30
            "TB" -> 1L shl 40
            else -> return 0L
        }
        return (value * mult).toLong()
    }
}
