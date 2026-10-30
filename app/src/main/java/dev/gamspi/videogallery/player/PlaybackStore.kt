package dev.gamspi.videogallery.player

import android.content.SharedPreferences

interface PlaybackStore {
    fun position(key: String): Long?
    fun savePosition(key: String, ms: Long)
    fun clearPosition(key: String)
    fun audioPreference(): AudioPreference?
    fun saveAudioPreference(p: AudioPreference)
}

class SharedPrefsPlaybackStore(private val prefs: SharedPreferences) : PlaybackStore {
    override fun position(key: String): Long? =
        if (prefs.contains(POS + key)) prefs.getLong(POS + key, 0L) else null

    override fun savePosition(key: String, ms: Long) {
        prefs.edit().putLong(POS + key, ms).apply()
    }

    override fun clearPosition(key: String) {
        prefs.edit().remove(POS + key).apply()
    }

    override fun audioPreference(): AudioPreference? {
        val lang = prefs.getString(AUDIO_LANG, null)?.takeIf { it.isNotBlank() } ?: return null
        return AudioPreference(lang, prefs.getString(AUDIO_LABEL, null))
    }

    override fun saveAudioPreference(p: AudioPreference) {
        prefs.edit().putString(AUDIO_LANG, p.language).putString(AUDIO_LABEL, p.label).apply()
    }

    private companion object {
        const val POS = "pos:"
        const val AUDIO_LANG = "audio_lang"
        const val AUDIO_LABEL = "audio_label"
    }
}
