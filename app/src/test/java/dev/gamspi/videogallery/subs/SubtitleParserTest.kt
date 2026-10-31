package dev.gamspi.videogallery.subs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleParserTest {
    private fun parse(text: String, name: String? = null) =
        SubtitleParser.parse(text.toByteArray(Charsets.UTF_8), name)

    @Test
    fun srt_basic() {
        val track = parse("1\n00:00:01,000 --> 00:00:02,500\nHello\nworld\n\n2\n00:00:03,000 --> 00:00:04,000\nBye\n", "a.srt")
        assertEquals(2, track.cues.size)
        assertEquals(SubtitleCue(1000, 2500, "Hello\nworld"), track.cues[0])
    }

    @Test
    fun srt_crlf_and_html_tags() {
        val track = parse("1\r\n00:00:01,000 --> 00:00:02,000\r\n<i>Hi</i> {\\an8}there\r\n", "a.srt")
        assertEquals("Hi there", track.cues[0].text)
    }

    @Test
    fun vtt_basic() {
        val track = parse("WEBVTT\n\n00:01.000 --> 00:02.000 align:start\nHi\n", "a.vtt")
        assertEquals(SubtitleCue(1000, 2000, "Hi"), track.cues[0])
    }

    @Test
    fun vtt_detected_by_content_without_extension() {
        val track = parse("WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHi\n")
        assertEquals(SubtitleCue(1000, 2000, "Hi"), track.cues[0])
    }

    @Test
    fun ass_dialogue() {
        val ass = "[Script Info]\nTitle: x\n\n[Events]\n" +
            "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
            "Dialogue: 0,0:00:01.50,0:00:03.00,Default,,0,0,0,,{\\i1}Line one\\NLine two\n"
        val track = parse(ass, "a.ass")
        assertEquals(SubtitleCue(1500, 3000, "Line one\nLine two"), track.cues[0])
    }

    @Test
    fun ass_text_keeps_commas() {
        val ass = "[Events]\n" +
            "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
            "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Hi, there, you\n"
        assertEquals("Hi, there, you", parse(ass, "a.ssa").cues[0].text)
    }

    @Test
    fun decodes_cp1251_fallback() {
        val bytes = "Привет".toByteArray(charset("windows-1251"))
        assertEquals("Привет", decodeSubtitleText(bytes))
    }

    @Test
    fun strips_utf8_bom() {
        val body = "1\n00:00:00,000 --> 00:00:01,000\nA\n".toByteArray(Charsets.UTF_8)
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + body
        assertEquals("A", SubtitleParser.parse(bytes, "a.srt").cues[0].text)
    }

    @Test
    fun garbage_gives_empty_track() {
        val track = parse("not a subtitle")
        assertNull(track.textAt(0))
        assertEquals(0, track.cues.size)
    }

    @Test
    fun malformed_input_does_not_throw() {
        assertNull(parse("00:00:xx,000 --> 00:00:02,000\nA\n", "a.srt").textAt(0))
        assertNull(SubtitleParser.parse(ByteArray(0), null).textAt(0))
    }
}
