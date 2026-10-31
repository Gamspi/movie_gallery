package dev.gamspi.videogallery.subs

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

object SubtitleParser {
    private val TIME = Regex("""(?:(\d+):)?(\d{1,2}):(\d{2})[,.](\d{1,3})""")
    private val TAGS = Regex("""<[^>]*>|\{[^}]*\}""")
    private val ASS_DEFAULT_FORMAT = listOf(
        "layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text",
    )

    private enum class Format { SRT, VTT, ASS }

    /** Never throws: unparseable input yields an empty track. */
    fun parse(bytes: ByteArray, fileName: String?): SubtitleTrack = try {
        val text = decodeSubtitleText(bytes)
        val cues = when (detect(text, fileName)) {
            Format.ASS -> parseAss(text)
            Format.VTT, Format.SRT -> parseBlocks(text)
        }
        SubtitleTrack(cues)
    } catch (e: Exception) {
        SubtitleTrack(emptyList())
    }

    private fun detect(text: String, fileName: String?): Format {
        when (fileName?.substringAfterLast('.', "")?.lowercase()) {
            "srt" -> return Format.SRT
            "vtt" -> return Format.VTT
            "ass", "ssa" -> return Format.ASS
        }
        return when {
            text.trimStart().startsWith("WEBVTT") -> Format.VTT
            text.contains("[Script Info]", ignoreCase = true) -> Format.ASS
            else -> Format.SRT
        }
    }

    // SRT and VTT share the "start --> end" line followed by text lines until a blank line.
    private fun parseBlocks(text: String): List<SubtitleCue> {
        val lines = text.lines()
        val cues = mutableListOf<SubtitleCue>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val arrow = line.indexOf("-->")
            if (arrow < 0) {
                i++
                continue
            }
            val start = parseTime(line.substring(0, arrow).trim())
            // Cue settings (VTT) follow the end time after whitespace.
            val end = parseTime(line.substring(arrow + 3).trim().substringBefore(' '))
            i++
            val body = mutableListOf<String>()
            while (i < lines.size && lines[i].isNotBlank()) {
                body += lines[i]
                i++
            }
            if (start == null || end == null) continue
            val clean = cleanText(body)
            if (clean.isNotEmpty()) cues += SubtitleCue(start, end, clean)
        }
        return cues
    }

    private fun parseAss(text: String): List<SubtitleCue> {
        var inEvents = false
        var format = ASS_DEFAULT_FORMAT
        val cues = mutableListOf<SubtitleCue>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                inEvents = line.equals("[Events]", ignoreCase = true)
                continue
            }
            if (!inEvents) continue
            when {
                line.startsWith("Format:", ignoreCase = true) ->
                    format = line.substringAfter(':').split(',').map { it.trim().lowercase() }
                line.startsWith("Dialogue:", ignoreCase = true) -> {
                    // Text is the last field and may itself contain commas.
                    val fields = line.substringAfter(':').trimStart().split(',', limit = format.size)
                    val start = fields.getOrNull(format.indexOf("start"))?.let { parseTime(it.trim()) }
                    val end = fields.getOrNull(format.indexOf("end"))?.let { parseTime(it.trim()) }
                    val body = fields.getOrNull(format.indexOf("text"))
                    if (start == null || end == null || body == null) continue
                    val clean = cleanText(TAGS.replace(body, "").replace("\\N", "\n").replace("\\n", "\n").lines())
                    if (clean.isNotEmpty()) cues += SubtitleCue(start, end, clean)
                }
            }
        }
        return cues
    }

    private fun parseTime(s: String): Long? {
        val m = TIME.matchEntire(s) ?: return null
        val (h, min, sec, frac) = m.destructured
        val ms = frac.padEnd(3, '0').toLong()
        return ((h.ifEmpty { "0" }.toLong() * 60 + min.toLong()) * 60 + sec.toLong()) * 1000 + ms
    }

    private fun cleanText(lines: List<String>): String =
        lines.map { TAGS.replace(it, "").trim() }.filter { it.isNotEmpty() }.joinToString("\n")
}

/** Strips a UTF-8 BOM; decodes as strict UTF-8, falling back to windows-1251. */
internal fun decodeSubtitleText(bytes: ByteArray): String {
    val hasBom = bytes.size >= 3 &&
        bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
    val body = if (hasBom) bytes.copyOfRange(3, bytes.size) else bytes
    return try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(body))
            .toString()
    } catch (e: java.nio.charset.CharacterCodingException) {
        String(body, charset("windows-1251"))
    }
}
