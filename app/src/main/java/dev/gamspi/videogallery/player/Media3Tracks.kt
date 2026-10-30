package dev.gamspi.videogallery.player

import androidx.media3.common.C
import androidx.media3.common.Tracks

/** Short human-readable codec name from an audio MIME type. */
fun codecName(mime: String?): String? = when {
    mime == null -> null
    mime == "audio/ac3" -> "AC3"
    mime == "audio/eac3" || mime == "audio/eac3-joc" -> "EAC3"
    mime.startsWith("audio/vnd.dts") -> "DTS"
    mime == "audio/true-hd" -> "TrueHD"
    mime == "audio/mp4a-latm" -> "AAC"
    else -> mime.substringAfter('/')
}

fun Tracks.audioTracks(): List<AudioTrack> = buildList {
    groups.forEachIndexed { gi, group ->
        if (group.type != C.TRACK_TYPE_AUDIO) return@forEachIndexed
        for (i in 0 until group.length) {
            val f = group.getTrackFormat(i)
            add(
                AudioTrack(
                    group = gi,
                    index = i,
                    language = f.language,
                    label = f.label,
                    codec = codecName(f.sampleMimeType),
                    supported = group.isTrackSupported(i),
                ),
            )
        }
    }
}

/** (group, index) of the currently selected audio track, if any. */
fun Tracks.selectedAudio(): Pair<Int, Int>? {
    groups.forEachIndexed { gi, group ->
        if (group.type != C.TRACK_TYPE_AUDIO) return@forEachIndexed
        for (i in 0 until group.length) if (group.isTrackSelected(i)) return gi to i
    }
    return null
}
