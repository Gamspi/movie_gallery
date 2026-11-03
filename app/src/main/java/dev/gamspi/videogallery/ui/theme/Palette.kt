package dev.gamspi.videogallery.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/** App palette: amber accent over the stock dark scheme surfaces. */
object AppColors {
    val Accent = Color(0xFFFFB300)
    val OnAccent = Color(0xFF1A1200)
    val Background = Color(0xFF1C1B1F)
    val Surface = Color(0xFF1C1B1F)
    val SurfaceVariant = Color(0xFF49454F)
    val Skeleton = Color(0xFF2B2930)
    val SkeletonHighlight = Color(0xFF3A3740)
}

/** WCAG 2.x contrast ratio between two opaque colors. */
fun contrastRatio(a: Color, b: Color): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

private fun relativeLuminance(c: Color): Double {
    fun lin(v: Float): Double {
        val d = v.toDouble()
        return if (d <= 0.03928) d / 12.92 else ((d + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
}
