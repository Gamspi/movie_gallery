package dev.gamspi.videogallery.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

object AppIcons {
    private var _search: ImageVector? = null
    private var _settings: ImageVector? = null
    private var _play: ImageVector? = null
    private var _pause: ImageVector? = null
    private var _chevronRight: ImageVector? = null
    private var _grid: ImageVector? = null
    private var _movie: ImageVector? = null
    private var _tv: ImageVector? = null
    private var _star: ImageVector? = null

    val Search: ImageVector
        get() = _search ?: ImageVector.Builder(
            name = "Search", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                moveTo(10.5f, 3f)
                curveTo(6.36f, 3f, 3f, 6.36f, 3f, 10.5f)
                curveTo(3f, 14.64f, 6.36f, 18f, 10.5f, 18f)
                curveTo(14.64f, 18f, 18f, 14.64f, 18f, 10.5f)
                curveTo(18f, 6.36f, 14.64f, 3f, 10.5f, 3f)
                close()
                moveTo(10.5f, 5.5f)
                curveTo(13.26f, 5.5f, 15.5f, 7.74f, 15.5f, 10.5f)
                curveTo(15.5f, 13.26f, 13.26f, 15.5f, 10.5f, 15.5f)
                curveTo(7.74f, 15.5f, 5.5f, 13.26f, 5.5f, 10.5f)
                curveTo(5.5f, 7.74f, 7.74f, 5.5f, 10.5f, 5.5f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(14.5f, 16.3f)
                lineTo(16.3f, 14.5f)
                lineTo(21.5f, 19.7f)
                lineTo(19.7f, 21.5f)
                close()
            }
        }.build().also { _search = it }

    val Settings: ImageVector
        get() = _settings ?: ImageVector.Builder(
            name = "Settings", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                moveTo(9.5f, 2f)
                lineTo(14.5f, 2f)
                lineTo(15f, 4.6f)
                lineTo(16.4f, 5.4f)
                lineTo(18.9f, 4.6f)
                lineTo(21.4f, 8.9f)
                lineTo(19.4f, 10.6f)
                lineTo(19.4f, 13.4f)
                lineTo(21.4f, 15.1f)
                lineTo(18.9f, 19.4f)
                lineTo(16.4f, 18.6f)
                lineTo(15f, 19.4f)
                lineTo(14.5f, 22f)
                lineTo(9.5f, 22f)
                lineTo(9f, 19.4f)
                lineTo(7.6f, 18.6f)
                lineTo(5.1f, 19.4f)
                lineTo(2.6f, 15.1f)
                lineTo(4.6f, 13.4f)
                lineTo(4.6f, 10.6f)
                lineTo(2.6f, 8.9f)
                lineTo(5.1f, 4.6f)
                lineTo(7.6f, 5.4f)
                lineTo(9f, 4.6f)
                close()
                moveTo(12f, 8f)
                curveTo(14.21f, 8f, 16f, 9.79f, 16f, 12f)
                curveTo(16f, 14.21f, 14.21f, 16f, 12f, 16f)
                curveTo(9.79f, 16f, 8f, 14.21f, 8f, 12f)
                curveTo(8f, 9.79f, 9.79f, 8f, 12f, 8f)
                close()
            }
        }.build().also { _settings = it }

    val Play: ImageVector
        get() = _play ?: ImageVector.Builder(
            name = "Play", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(7f, 3f)
                lineTo(21f, 12f)
                lineTo(7f, 21f)
                close()
            }
        }.build().also { _play = it }

    val Pause: ImageVector
        get() = _pause ?: ImageVector.Builder(
            name = "Pause", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(6f, 4f)
                lineTo(10f, 4f)
                lineTo(10f, 20f)
                lineTo(6f, 20f)
                close()
                moveTo(14f, 4f)
                lineTo(18f, 4f)
                lineTo(18f, 20f)
                lineTo(14f, 20f)
                close()
            }
        }.build().also { _pause = it }

    val ChevronRight: ImageVector
        get() = _chevronRight ?: ImageVector.Builder(
            name = "ChevronRight", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(8.5f, 4.5f)
                lineTo(6.5f, 6.5f)
                lineTo(12f, 12f)
                lineTo(6.5f, 17.5f)
                lineTo(8.5f, 19.5f)
                lineTo(16f, 12f)
                close()
            }
        }.build().also { _chevronRight = it }

    /** Four tiles: «Все». */
    val Grid: ImageVector
        get() = _grid ?: ImageVector.Builder(
            name = "Grid", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(4f, 4f); lineTo(10.5f, 4f); lineTo(10.5f, 10.5f); lineTo(4f, 10.5f); close()
                moveTo(13.5f, 4f); lineTo(20f, 4f); lineTo(20f, 10.5f); lineTo(13.5f, 10.5f); close()
                moveTo(4f, 13.5f); lineTo(10.5f, 13.5f); lineTo(10.5f, 20f); lineTo(4f, 20f); close()
                moveTo(13.5f, 13.5f); lineTo(20f, 13.5f); lineTo(20f, 20f); lineTo(13.5f, 20f); close()
            }
        }.build().also { _grid = it }

    /** Film strip: «Фильмы». */
    val Movie: ImageVector
        get() = _movie ?: ImageVector.Builder(
            name = "Movie", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                moveTo(3f, 4f); lineTo(21f, 4f); lineTo(21f, 20f); lineTo(3f, 20f); close()
                // Perforation holes on both edges and the frame in the middle.
                moveTo(4.5f, 5.5f); lineTo(6.5f, 5.5f); lineTo(6.5f, 7.5f); lineTo(4.5f, 7.5f); close()
                moveTo(4.5f, 10f); lineTo(6.5f, 10f); lineTo(6.5f, 12f); lineTo(4.5f, 12f); close()
                moveTo(4.5f, 14.5f); lineTo(6.5f, 14.5f); lineTo(6.5f, 16.5f); lineTo(4.5f, 16.5f); close()
                moveTo(17.5f, 5.5f); lineTo(19.5f, 5.5f); lineTo(19.5f, 7.5f); lineTo(17.5f, 7.5f); close()
                moveTo(17.5f, 10f); lineTo(19.5f, 10f); lineTo(19.5f, 12f); lineTo(17.5f, 12f); close()
                moveTo(17.5f, 14.5f); lineTo(19.5f, 14.5f); lineTo(19.5f, 16.5f); lineTo(17.5f, 16.5f); close()
                moveTo(8.5f, 6f); lineTo(15.5f, 6f); lineTo(15.5f, 18f); lineTo(8.5f, 18f); close()
            }
        }.build().also { _movie = it }

    /** Television: «Сериалы». */
    val Tv: ImageVector
        get() = _tv ?: ImageVector.Builder(
            name = "Tv", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                moveTo(3f, 5f); lineTo(21f, 5f); lineTo(21f, 17f); lineTo(3f, 17f); close()
                moveTo(5f, 7f); lineTo(19f, 7f); lineTo(19f, 15f); lineTo(5f, 15f); close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(8f, 19f); lineTo(16f, 19f); lineTo(16f, 21f); lineTo(8f, 21f); close()
            }
        }.build().also { _tv = it }

    /** Five-point star: «Для вас». */
    val Star: ImageVector
        get() = _star ?: ImageVector.Builder(
            name = "Star", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 2.5f); lineTo(14.9f, 8.6f); lineTo(21.5f, 9.3f); lineTo(16.6f, 13.8f)
                lineTo(17.9f, 20.4f); lineTo(12f, 17.1f); lineTo(6.1f, 20.4f); lineTo(7.4f, 13.8f)
                lineTo(2.5f, 9.3f); lineTo(9.1f, 8.6f); close()
            }
        }.build().also { _star = it }
}
