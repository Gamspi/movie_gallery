package dev.gamspi.videogallery.ui.nav

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import dev.gamspi.videogallery.catalog.model.MediaKind
import dev.gamspi.videogallery.details.DetailsScreen
import dev.gamspi.videogallery.gallery.GalleryScreen
import dev.gamspi.videogallery.player.PlayerScreen
import dev.gamspi.videogallery.releases.ReleasesScreen
import dev.gamspi.videogallery.search.SearchScreen
import dev.gamspi.videogallery.settings.KEY_SERVICE_ROUTE
import dev.gamspi.videogallery.settings.KeyServiceScreen
import dev.gamspi.videogallery.settings.SETTINGS_HOME_ROUTE
import dev.gamspi.videogallery.settings.SETTINGS_SECTION_ROUTE
import dev.gamspi.videogallery.settings.SettingsHomeScreen
import dev.gamspi.videogallery.settings.SettingsSection
import dev.gamspi.videogallery.settings.SettingsSectionScreen
import dev.gamspi.videogallery.settings.keyServiceOf
import dev.gamspi.videogallery.settings.keyServiceRoute
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
    NavHost(
        navController = nav,
        startDestination = "gallery",
        enterTransition = { if (touchesPlayer()) EnterTransition.None else fadeIn(tween(200)) },
        exitTransition = { if (touchesPlayer()) ExitTransition.None else fadeOut(tween(200)) },
        popEnterTransition = { if (touchesPlayer()) EnterTransition.None else fadeIn(tween(200)) },
        popExitTransition = { if (touchesPlayer()) ExitTransition.None else fadeOut(tween(200)) },
    ) {
        composable("gallery") { entry ->
            GalleryScreen(
                onOpenMovie = { id, kind -> nav.navigateOnce(entry, movieRoute(id, kind)) },
                onContinue = { nav.navigateOnce(entry, it.streamRoute()) },
                onOpenSearch = { nav.navigateOnce(entry, "search") },
                onOpenSettings = { nav.navigateOnce(entry, SETTINGS_HOME_ROUTE) },
                onOpenKeySettings = { nav.navigateOnce(entry, settingsRoute(SettingsSection.Keys)) },
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
                    onOpenKeyService = { nav.navigateOnce(entry, keyServiceRoute(it)) },
                )
            }
        }
        composable(
            KEY_SERVICE_ROUTE,
            arguments = listOf(navArgument("service") { type = NavType.StringType }),
        ) { entry ->
            val service = keyServiceOf(entry.arguments?.getString("service"))
            if (service == null) {
                LaunchedEffect(Unit) { nav.popBackStack() }
            } else {
                KeyServiceScreen(service = service, onBack = { nav.popBackStack() })
            }
        }
        composable(
            PLAYER_ROUTE,
            arguments = PLAYER_ARGS.map {
                navArgument(it) { type = NavType.StringType; defaultValue = "" }
            },
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) { PlayerScreen(onBack = { nav.popBackStack() }) }
        composable("search") { entry ->
            SearchScreen(
                onOpenMovie = { nav.navigateOnce(entry, movieRoute(it)) },
                onOpenKeySettings = { nav.navigateOnce(entry, settingsRoute(SettingsSection.Keys)) },
                onOpenSettings = { nav.navigateOnce(entry, SETTINGS_HOME_ROUTE) },
            )
        }
        composable(MOVIE_ROUTE, arguments = listOf(
            navArgument("id") { type = NavType.IntType },
            navArgument("kind") { type = NavType.StringType; defaultValue = MediaKind.Movie.name },
        )) { entry ->
            DetailsScreen(
                onOpenKeySettings = { nav.navigateOnce(entry, settingsRoute(SettingsSection.Keys)) },
                onOpenSettings = { nav.navigateOnce(entry, SETTINGS_HOME_ROUTE) },
                onWatch = {
                    val kind = MediaKind.entries.firstOrNull { it.name == entry.arguments?.getString("kind") }
                        ?: MediaKind.Movie
                    nav.navigateOnce(entry, releasesRoute(checkNotNull(entry.arguments).getInt("id"), kind))
                },
            )
        }
        composable(RELEASES_ROUTE, arguments = listOf(
            navArgument("id") { type = NavType.IntType },
            navArgument("kind") { type = NavType.StringType; defaultValue = MediaKind.Movie.name },
            navArgument("season") { type = NavType.StringType; defaultValue = "" },
        )) { entry ->
            ReleasesScreen(
                onOpenKeySettings = { nav.navigateOnce(entry, settingsRoute(SettingsSection.Keys)) },
                onOpenSettings = { nav.navigateOnce(entry, SETTINGS_HOME_ROUTE) },
                onPlay = { r, meta -> nav.navigateOnce(entry, streamRoute(r.title, meta)) },
            )
        }
        composable(
            STREAM_ROUTE,
            arguments = STREAM_ARGS.map {
                navArgument(it) { type = NavType.StringType; defaultValue = "" }
            },
        ) { entry ->
            StreamScreen(
                onReady = { url, key, title, meta ->
                    nav.navigateOnce(entry, playerRoute(url, title, key, meta)) {
                        popUpTo(STREAM_ROUTE) { inclusive = true }
                    }
                },
                onBack = { nav.popBackStack() },
                onEpisodeMissing = { season ->
                    val id = entry.arguments?.getString("movieId")?.toIntOrNull() ?: 0
                    if (id > 0) {
                        nav.navigateOnce(entry, releasesRoute(id, MediaKind.Series, season)) {
                            popUpTo(STREAM_ROUTE) { inclusive = true }
                        }
                    } else {
                        nav.popBackStack()
                    }
                },
            )
        }
    }
    }
}

// Player transitions are instant: either side of the transition is the player.
private fun AnimatedContentTransitionScope<NavBackStackEntry>.touchesPlayer(): Boolean =
    initialState.destination.route?.startsWith("player") == true ||
        targetState.destination.route?.startsWith("player") == true

/** Ignore repeated clicks (double OK) once the source screen is no longer resumed. */
private fun NavController.navigateOnce(
    from: NavBackStackEntry,
    route: String,
    builder: NavOptionsBuilder.() -> Unit = {},
) {
    if (from.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) navigate(route, builder)
}
