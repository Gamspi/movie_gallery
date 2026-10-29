package dev.gamspi.videogallery.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.tv.material3.Text
import dev.gamspi.videogallery.gallery.GalleryScreen
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
        // Stubs: replaced by Task 5 / Task 6
        composable("search") { Stub("Поиск — скоро") }
        composable("movie/{id}", arguments = listOf(navArgument("id") { type = NavType.IntType })) {
            Stub("Фильм #${it.arguments?.getInt("id")} — скоро")
        }
    }
}

@Composable
private fun Stub(text: String) {
    Box(Modifier.fillMaxSize(), Alignment.Center) { Text(text) }
}
