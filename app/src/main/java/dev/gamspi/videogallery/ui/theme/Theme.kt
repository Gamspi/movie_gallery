package dev.gamspi.videogallery.ui.theme

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import dev.gamspi.videogallery.R
import androidx.compose.material3.MaterialTheme as M3MaterialTheme
import androidx.compose.material3.Typography as M3Typography
import androidx.compose.material3.darkColorScheme as m3DarkColorScheme

val Montserrat = FontFamily(
    Font(R.font.montserrat_regular, FontWeight.Normal),
    Font(R.font.montserrat_medium, FontWeight.Medium),
    Font(R.font.montserrat_semibold, FontWeight.SemiBold),
    Font(R.font.montserrat_bold, FontWeight.Bold),
)

private fun tvTypography(): Typography {
    val d = Typography()
    return Typography(
        displayLarge = d.displayLarge.copy(fontFamily = Montserrat),
        displayMedium = d.displayMedium.copy(fontFamily = Montserrat),
        displaySmall = d.displaySmall.copy(fontFamily = Montserrat),
        headlineLarge = d.headlineLarge.copy(fontFamily = Montserrat),
        headlineMedium = d.headlineMedium.copy(fontFamily = Montserrat),
        headlineSmall = d.headlineSmall.copy(fontFamily = Montserrat),
        titleLarge = d.titleLarge.copy(fontFamily = Montserrat),
        titleMedium = d.titleMedium.copy(fontFamily = Montserrat),
        titleSmall = d.titleSmall.copy(fontFamily = Montserrat),
        bodyLarge = d.bodyLarge.copy(fontFamily = Montserrat),
        bodyMedium = d.bodyMedium.copy(fontFamily = Montserrat),
        bodySmall = d.bodySmall.copy(fontFamily = Montserrat),
        labelLarge = d.labelLarge.copy(fontFamily = Montserrat),
        labelMedium = d.labelMedium.copy(fontFamily = Montserrat),
        labelSmall = d.labelSmall.copy(fontFamily = Montserrat),
    )
}

private fun m3Typography(): M3Typography {
    val d = M3Typography()
    return M3Typography(
        displayLarge = d.displayLarge.copy(fontFamily = Montserrat),
        displayMedium = d.displayMedium.copy(fontFamily = Montserrat),
        displaySmall = d.displaySmall.copy(fontFamily = Montserrat),
        headlineLarge = d.headlineLarge.copy(fontFamily = Montserrat),
        headlineMedium = d.headlineMedium.copy(fontFamily = Montserrat),
        headlineSmall = d.headlineSmall.copy(fontFamily = Montserrat),
        titleLarge = d.titleLarge.copy(fontFamily = Montserrat),
        titleMedium = d.titleMedium.copy(fontFamily = Montserrat),
        titleSmall = d.titleSmall.copy(fontFamily = Montserrat),
        bodyLarge = d.bodyLarge.copy(fontFamily = Montserrat),
        bodyMedium = d.bodyMedium.copy(fontFamily = Montserrat),
        bodySmall = d.bodySmall.copy(fontFamily = Montserrat),
        labelLarge = d.labelLarge.copy(fontFamily = Montserrat),
        labelMedium = d.labelMedium.copy(fontFamily = Montserrat),
        labelSmall = d.labelSmall.copy(fontFamily = Montserrat),
    )
}

@Composable
fun VideoGalleryTheme(content: @Composable () -> Unit) {
    val tvType = remember { tvTypography() }
    val m3Type = remember { m3Typography() }
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = AppColors.Accent,
            onPrimary = AppColors.OnAccent,
            border = AppColors.Accent,
            surfaceTint = AppColors.Accent,
        ),
        typography = tvType,
    ) {
        // Dark M3 scheme for compose-material3 widgets (OutlinedTextField).
        M3MaterialTheme(
            colorScheme = m3DarkColorScheme(
                primary = AppColors.Accent,
                onPrimary = AppColors.OnAccent,
            ),
            typography = m3Type,
        ) {
            // tv-material LocalContentColor defaults to black; Surface provides onSurface
            // (light) as content color for every Text outside Card/Button.
            Surface(modifier = Modifier.fillMaxSize()) {
                content()
            }
        }
    }
}
