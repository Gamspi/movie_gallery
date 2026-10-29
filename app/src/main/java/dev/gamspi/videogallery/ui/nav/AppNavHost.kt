package dev.gamspi.videogallery.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.gamspi.videogallery.details.DetailsScreen
import dev.gamspi.videogallery.gallery.GalleryScreen
import dev.gamspi.videogallery.search.SearchScreen
import dev.gamspi.videogallery.settings.SettingsScreen

@Composable
fun AppNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "gallery") {
        composable("gallery") {
            GalleryScreen(
                onOpenMovie = { nav.navigate("movie/$it") },
                onOpenSearch = { nav.navigate("search") },
                onOpenSettings = { nav.navigate("settings") },
            )
        }
        composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
        composable("search") {
            SearchScreen(
                onOpenMovie = { nav.navigate("movie/$it") },
                onOpenSettings = { nav.navigate("settings") },
            )
        }
        composable("movie/{id}", arguments = listOf(navArgument("id") { type = NavType.IntType })) {
            DetailsScreen(onOpenSettings = { nav.navigate("settings") })
        }
    }
}
