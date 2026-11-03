package dev.gamspi.videogallery.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import org.junit.Test
import org.junit.Assert.assertTrue

class PaletteTest {
    @Test
    fun accentBorderContrastAgainstBackground() {
        assertTrue(contrastRatio(AppColors.Accent, AppColors.Background) >= 3.0)
    }

    @Test
    fun onAccentTextContrast() {
        assertTrue(contrastRatio(AppColors.OnAccent, AppColors.Accent) >= 4.5)
    }

    @Test
    fun contrastOfBlackAndWhiteIs21() {
        assertTrue(abs(contrastRatio(Color.Black, Color.White) - 21.0) < 0.01)
    }
}
