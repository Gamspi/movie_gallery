package dev.gamspi.videogallery.ui.nav

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.navigation.navArgument
import dev.gamspi.videogallery.details.DetailsScreen
import dev.gamspi.videogallery.gallery.GalleryScreen
import dev.gamspi.videogallery.player.PlayerScreen
import dev.gamspi.videogallery.releases.ReleasesScreen
import dev.gamspi.videogallery.search.SearchScreen
import dev.gamspi.videogallery.settings.SettingsScreen
import dev.gamspi.videogallery.settings.SettingsViewModel
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
                onOpenSettings = { nav.navigateOnce(entry, "settings") },
            )
        }
        composable("settings") { entry ->
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onPlayTest = { url ->
                    nav.navigateOnce(entry, "player?url=${Uri.encode(url)}&title=${Uri.encode("Тест")}")
                },
            )
        }
        composable(
            "player?url={url}&title={title}&key={key}",
            arguments = listOf("url", "title", "key").map {
                navArgument(it) { type = NavType.StringType; defaultValue = "" }
            },
        ) { PlayerScreen(onBack = { nav.popBackStack() }) }
        composable("search") { entry ->
            SearchScreen(
                onOpenMovie = { nav.navigateOnce(entry, "movie/$it") },
                onOpenSettings = { nav.navigateOnce(entry, "settings") },
            )
        }
        composable("movie/{id}", arguments = listOf(navArgument("id") { type = NavType.IntType })) { entry ->
            DetailsScreen(
                onOpenSettings = { nav.navigateOnce(entry, "settings") },
                onWatch = { nav.navigateOnce(entry, "releases/${entry.arguments?.getInt("id")}") },
            )
        }
        composable("releases/{id}", arguments = listOf(navArgument("id") { type = NavType.IntType })) { entry ->
            ReleasesScreen(onOpenSettings = { nav.navigateOnce(entry, "settings") })
        }
    }
    }
}

/** Ignore repeated clicks (double OK) once the source screen is no longer resumed. */
private fun NavController.navigateOnce(from: NavBackStackEntry, route: String) {
    if (from.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) navigate(route)
}
