package dev.gamspi.videogallery.player

class FakePlaybackStore : PlaybackStore {
    val positions = mutableMapOf<String, Long>()
    var preference: AudioPreference? = null
    var saveCount = 0

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
}
