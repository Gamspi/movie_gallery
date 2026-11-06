package dev.gamspi.videogallery

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dagger.hilt.android.HiltAndroidApp
import dev.gamspi.videogallery.net.ProxyInterceptor
import dev.gamspi.videogallery.net.proxiedBy
import javax.inject.Inject
import okhttp3.OkHttpClient

@HiltAndroidApp
class App : Application(), SingletonImageLoader.Factory {
    @Inject lateinit var proxy: ProxyInterceptor

    // Default caches; only the network client changes so TMDB posters can use the proxy.
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val client = OkHttpClient.Builder().proxiedBy(proxy).build()
        return ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
            .build()
    }
}
