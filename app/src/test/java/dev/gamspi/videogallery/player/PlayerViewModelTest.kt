package dev.gamspi.videogallery.player

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerViewModelTest {
    private val store = FakePlaybackStore()
    private var now = 0L

    private fun vm(url: String = "http://x/a.mp4", key: String = "") = PlayerViewModel(
        SavedStateHandle(mapOf("url" to url, "title" to "T", "key" to key)),
        store,
    ) { now }

    private fun track(lang: String?, label: String? = null) =
        AudioTrack(0, 0, lang, label, "aac", true)

    @Test
    fun resumeOffer_fromStore() {
        store.positions["k"] = 120_000
        val vm = vm(key = "k")
        vm.onDuration(3_600_000)
        assertEquals(120_000L, vm.state.value.resumeOffer)
        vm.dismissResume()
        assertNull(vm.state.value.resumeOffer)
        vm.onDuration(3_600_000)
        assertNull(vm.state.value.resumeOffer)
    }

    @Test
    fun resumeOffer_nullWhenShort() {
        store.positions["k"] = 30_000
        val vm = vm(key = "k")
        vm.onDuration(3_600_000)
        assertNull(vm.state.value.resumeOffer)
    }

    @Test
    fun progress_throttled10s() {
        val vm = vm(key = "k")
        now = 0; vm.onProgress(1_000, 3_600_000)
        now = 2_000; vm.onProgress(3_000, 3_600_000)
        now = 5_000; vm.onProgress(6_000, 3_600_000)
        assertEquals(1, store.saveCount)
        now = 10_000; vm.onProgress(11_000, 3_600_000)
        assertEquals(2, store.saveCount)
        assertEquals(11_000L, store.positions["k"])
    }

    @Test
    fun exit_savesImmediately() {
        val vm = vm(key = "k")
        vm.onPauseOrExit(500_000, 3_600_000)
        assertEquals(500_000L, store.positions["k"])
    }

    @Test
    fun exit_finished_clears() {
        store.positions["k"] = 100_000
        val vm = vm(key = "k")
        vm.onPauseOrExit(3_500_000, 3_600_000)
        assertNull(store.positions["k"])
    }

    @Test
    fun exit_zero_keepsSaved() {
        store.positions["k"] = 100_000
        val vm = vm(key = "k")
        vm.onPauseOrExit(0, 3_600_000)
        assertEquals(100_000L, store.positions["k"])
        assertEquals(0, store.saveCount)
    }

    @Test
    fun audioSelected_savesPreference() {
        val vm = vm()
        vm.onAudioSelected(track("ru", "Дубляж"))
        assertEquals(AudioPreference("ru", "Дубляж"), store.preference)
        store.preference = null
        vm.onAudioSelected(track(null))
        assertNull(store.preference)
        vm.onAudioSelected(track("und"))
        assertNull(store.preference)
    }

    @Test
    fun initialAudio_usesPreference() {
        store.preference = AudioPreference("ru", null)
        val vm = vm()
        val ru = track("ru")
        assertEquals(ru, vm.chooseInitialAudio(listOf(track("en"), ru)))
    }

    @Test
    fun keyDefaultsToUrl() {
        val vm = vm(url = "http://x/a.mp4")
        vm.onPauseOrExit(500_000, 3_600_000)
        assertEquals(500_000L, store.positions["url:http://x/a.mp4"])
    }
}
