package dev.gamspi.videogallery.player

import dev.gamspi.videogallery.subs.Layer

class FakePlaybackStore : PlaybackStore {
    val positions = mutableMapOf<String, Long>()
    var preference: AudioPreference? = null
    var saveCount = 0
    val subtitleChoices = mutableMapOf<Pair<String, Layer>, SubtitleChoice>()

    override fun position(key: String): Long? = positions[key]

    override fun savePosition(key: String, ms: Long) {
        saveCount++
        positions[key] = ms
    }

    override fun clearPosition(key: String) {
        positions.remove(key)
    }

    override fun audioPreference(): AudioPreference? = preference

    override fun saveAudioPreference(p: AudioPreference) {
        preference = p
    }

    override fun subtitleChoice(titleKey: String, layer: Layer): SubtitleChoice? = subtitleChoices[titleKey to layer]

    override fun saveSubtitleChoice(titleKey: String, layer: Layer, choice: SubtitleChoice) {
        subtitleChoices[titleKey to layer] = choice
    }
}
