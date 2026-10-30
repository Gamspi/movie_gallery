package dev.gamspi.videogallery.stream

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore

/** Clears the view model the way the framework does: through its ViewModelStore. */
fun <T : ViewModel> T.clearViaStore() {
    val vm = this
    val store = ViewModelStore()
    ViewModelProvider(
        store,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <V : ViewModel> create(modelClass: Class<V>): V = vm as V
        },
    ).get(vm.javaClass)
    store.clear()
}
