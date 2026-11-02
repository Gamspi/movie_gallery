package dev.gamspi.videogallery.translate

import kotlinx.coroutines.flow.MutableStateFlow

class FakeTranslationModels(initial: ModelState = ModelState.Ready) : TranslationModels {
    override val state = MutableStateFlow(initial)
    var ensureCalls = 0

    /** Runs inside ensureDownloaded after state becomes Downloading; should set the final state. */
    var onEnsure: suspend () -> Unit = { state.value = ModelState.Ready }

    override suspend fun ensureDownloaded() {
        ensureCalls++
        state.value = ModelState.Downloading(null)
        onEnsure()
    }

    override suspend fun delete() {
        state.value = ModelState.NotDownloaded
    }
}
