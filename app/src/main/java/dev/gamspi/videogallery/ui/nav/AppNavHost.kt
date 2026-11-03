package dev.gamspi.videogallery.ui.nav

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.navArgument
import dev.gamspi.videogallery.details.DetailsScreen
import dev.gamspi.videogallery.gallery.GalleryScreen
import dev.gamspi.videogallery.player.PlayerScreen
import dev.gamspi.videogallery.releases.ReleasesScreen
import dev.gamspi.videogallery.search.SearchScreen
import dev.gamspi.videogallery.settings.SETTINGS_HOME_ROUTE
import dev.gamspi.videogallery.settings.SETTINGS_SECTION_ROUTE
import dev.gamspi.videogallery.settings.SettingsHomeScreen
import dev.gamspi.videogallery.settings.SettingsSection
import dev.gamspi.videogallery.settings.SettingsSectionScreen
import dev.gamspi.videogallery.settings.sectionOf
import dev.gamspi.videogallery.settings.settingsRoute
import dev.gamspi.videogallery.settings.SettingsViewModel
import dev.gamspi.videogallery.stream.StreamScreen
import dev.gamspi.videogallery.ui.components.LocalCatalogSource

@Composable
fun AppNavHost() {
    val nav = rememberNavController()
    val source by hiltViewModel<SettingsViewModel>().catalogSource.collectAsState()
    CompositionLocalProvider(LocalCatalogSource provides source) {
    NavHost(navController = nav, startDestination = "gallery") {
        composable("gallery") { entry ->
            GalleryScreen(
                onOpenMovie = { nav.navigateOnce(entry, "movie/$it") },
                onOpenSearch = { nav.navigateOnce(entry, "search") },
                onOpenSettings = { nav.navigateOnce(entry, SETTINGS_HOME_ROUTE) },
                onOpenCatalogSettings = { nav.navigateOnce(entry, settingsRoute(SettingsSection.Catalog)) },
            )
        }
        composable(SETTINGS_HOME_ROUTE) { entry ->
            SettingsHomeScreen(
                onOpenSection = { nav.navigateOnce(entry, settingsRoute(it)) },
            )
        }
        composable(
            SETTINGS_SECTION_ROUTE,
            arguments = listOf(navArgument("section") { type = NavType.StringType }),
        ) { entry ->
            val section = sectionOf(entry.arguments?.getString("section"))
            if (section == null) {
                LaunchedEffect(Unit) { nav.popBackStack() }
            } else {
                SettingsSectionScreen(
                    section = section,
                    onBack = { nav.popBackStack() },
                    onPlayTest = { url ->
                        nav.navigateOnce(entry, "player?url=${Uri.encode(url)}&title=${Uri.encode("Тест")}")
                    },
                )
            }
        }
        composable(
            "player?url={url}&title={title}&key={key}&imdb={imdb}",
            arguments = listOf("url", "title", "key", "imdb").map {
                navArgument(it) { type = NavType.StringType; defaultValue = "" }
            },
        ) { PlayerScreen(onBack = { nav.popBackStack() }) }
        composable("search") { entry ->
            SearchScreen(
                onOpenMovie = { nav.navigateOnce(entry, "movie/$it") },
                onOpenSettings = { nav.navigateOnce(entry, settingsRoute(SettingsSection.Catalog)) },
            )
        }
        composable("movie/{id}", arguments = listOf(navArgument("id") { type = NavType.IntType })) { entry ->
            DetailsScreen(
                onOpenSettings = { nav.navigateOnce(entry, settingsRoute(SettingsSection.Catalog)) },
                onWatch = { nav.navigateOnce(entry, "releases/${entry.arguments?.getInt("id")}") },
            )
        }
        composable("releases/{id}", arguments = listOf(navArgument("id") { type = NavType.IntType })) { entry ->
            ReleasesScreen(
                onOpenSettings = { nav.navigateOnce(entry, settingsRoute(SettingsSection.Catalog)) },
                onPlay = { r, imdb ->
                    nav.navigateOnce(
                        entry,
                        "stream?magnet=${Uri.encode(r.magnet)}&title=${Uri.encode(r.title)}" +
                            "&imdb=${Uri.encode(imdb.orEmpty())}&torrent=${Uri.encode(r.torrentUrl.orEmpty())}",
                    )
                },
            )
        }
        composable(
            STREAM_ROUTE,
            arguments = listOf("magnet", "title", "imdb", "torrent").map {
                navArgument(it) { type = NavType.StringType; defaultValue = "" }
            },
        ) { entry ->
            StreamScreen(
                onReady = { url, key, title, imdb ->
                    nav.navigateOnce(
                        entry,
                        "player?url=${Uri.encode(url)}&title=${Uri.encode(title)}&key=${Uri.encode(key)}" +
                            "&imdb=${Uri.encode(imdb)}",
                    ) { popUpTo(STREAM_ROUTE) { inclusive = true } }
                },
                onBack = { nav.popBackStack() },
            )
        }
    }
    }
}

/** Ignore repeated clicks (double OK) once the source screen is no longer resumed. */
private fun NavController.navigateOnce(
    from: NavBackStackEntry,
    route: String,
    builder: NavOptionsBuilder.() -> Unit = {},
) {
    if (from.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) navigate(route, builder)
}

private const val STREAM_ROUTE = "stream?magnet={magnet}&title={title}&imdb={imdb}&torrent={torrent}"
