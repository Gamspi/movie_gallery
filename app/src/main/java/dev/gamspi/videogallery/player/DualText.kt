package dev.gamspi.videogallery.player

import android.content.Context
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.ExoTrackSelection

/** Empty slots must stay empty, even when the other layer is enabled. */
fun textSlotDefinitions(
    textRenderers: List<Int>,
    a: Pair<Int, Int>?,
    b: Pair<Int, Int>?,
): Map<Int, Pair<Int, Int>> = buildMap {
    textRenderers.getOrNull(0)?.let { renderer -> a?.let { put(renderer, it) } }
    // ProgressiveMediaPeriod permits only one stream per TrackGroup.
    textRenderers.getOrNull(1)?.let { renderer ->
        b?.takeUnless { a?.first == it.first }?.let { put(renderer, it) }
    }
}

class DualTextRenderersFactory(
    context: Context,
    private val outA: (List<Cue>) -> Unit,
    private val outB: (List<Cue>) -> Unit,
) : DefaultRenderersFactory(context) {
    init {
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)
        setEnableDecoderFallback(true)
    }

    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>,
    ) {
        fun textOutput(callback: (List<Cue>) -> Unit) = object : TextOutput {
            override fun onCues(cueGroup: CueGroup) = callback(cueGroup.cues)
        }
        out.add(TextRenderer(textOutput(outA), outputLooper))
        out.add(TextRenderer(textOutput(outB), outputLooper))
    }
}

class DualTextTrackSelector(context: Context) : DefaultTrackSelector(context) {
    // UI writes, playback thread reads: publish both slots as one immutable snapshot.
    @Volatile private var slots: Pair<Pair<Int, Int>?, Pair<Int, Int>?> = null to null

    fun setTextSlots(a: Pair<Int, Int>?, b: Pair<Int, Int>?) {
        val next = a to b
        if (slots == next) return
        slots = next
        invalidate(null)
    }

    override fun selectAllTracks(
        definitions: Array<ExoTrackSelection.Definition?>,
        mappedTrackInfo: MappedTrackInfo,
        rendererFormatSupports: Array<Array<IntArray>>,
        rendererMixedMimeTypeAdaptationSupports: IntArray,
        params: Parameters,
    ) {
        super.selectAllTracks(
            definitions, mappedTrackInfo, rendererFormatSupports,
            rendererMixedMimeTypeAdaptationSupports, params,
        )
        val textRenderers = (0 until mappedTrackInfo.rendererCount)
            .filter { mappedTrackInfo.getRendererType(it) == C.TRACK_TYPE_TEXT }
        textRenderers.forEach { definitions[it] = null }

        // TrackSelectionUtil.buildTracks flattens mapped groups in renderer order.
        // Mapping assigns each group once, not once for each compatible renderer.
        val groups = (0 until mappedTrackInfo.rendererCount).flatMap { renderer ->
            val tracks = mappedTrackInfo.getTrackGroups(renderer)
            (0 until tracks.length).map { group -> Triple(tracks[group], renderer, group) }
        }
        val snapshot = slots
        textSlotDefinitions(textRenderers, snapshot.first, snapshot.second).forEach { (renderer, slot) ->
            val (group, owner, localGroup) = groups.getOrNull(slot.first) ?: return@forEach
            if (group.type != C.TRACK_TYPE_TEXT || slot.second !in 0 until group.length) return@forEach
            // Both factory renderers are identical TextRenderers; use the mapped owner's support.
            if (RendererCapabilities.getFormatSupport(rendererFormatSupports[owner][localGroup][slot.second]) !=
                C.FORMAT_HANDLED
            ) return@forEach
            definitions[renderer] = ExoTrackSelection.Definition(group, slot.second)
        }
    }
}
