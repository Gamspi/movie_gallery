package dev.gamspi.videogallery.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.gamspi.videogallery.ui.theme.AppColors

/**
 * Shared shimmer clock. The animation runs only while at least one [shimmer] user is composed
 * and the progress is read in draw only, so cards never recompose per frame.
 */
@Stable
class ShimmerClock {
    var users by mutableIntStateOf(0)
    val progress = mutableFloatStateOf(0f)
}

private val LocalShimmerClock = compositionLocalOf<ShimmerClock?> { null }

@Composable
fun ShimmerHost(content: @Composable () -> Unit) {
    if (LocalShimmerClock.current != null) {
        content() // already inside a host: reuse its clock
        return
    }
    val clock = remember { ShimmerClock() }
    CompositionLocalProvider(LocalShimmerClock provides clock) {
        ShimmerDriver(clock)
        content()
    }
}

// Separate composable so that only it recomposes when the number of users changes.
@Composable
private fun ShimmerDriver(clock: ShimmerClock) {
    val active = clock.users > 0
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        animate(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        ) { value, _ -> clock.progress.floatValue = value }
    }
}

/** Skeleton fill with a moving highlight; static fill when no [ShimmerHost] is above. */
fun Modifier.shimmer(): Modifier = composed {
    val clock = LocalShimmerClock.current
    if (clock != null) {
        DisposableEffect(clock) {
            clock.users++
            onDispose { clock.users-- }
        }
    }
    drawWithCache {
        val band = size.width * 0.6f
        val brush = Brush.linearGradient(
            colors = listOf(AppColors.Skeleton, AppColors.SkeletonHighlight, AppColors.Skeleton),
            start = Offset(0f, 0f),
            end = Offset(band, 0f),
        )
        onDrawBehind {
            drawRect(AppColors.Skeleton)
            if (clock != null) {
                val x = -band + clock.progress.floatValue * (size.width + band)
                clipRect {
                    translate(left = x) { drawRect(brush, size = Size(band, size.height)) }
                }
            }
        }
    }
}

@Composable
fun SkeletonCard(modifier: Modifier = Modifier) {
    Column(modifier = modifier.width(150.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp)).shimmer(),
        )
        SkeletonBar(widthFraction = 0.85f, height = 14.dp, topPadding = 8.dp)
        SkeletonBar(widthFraction = 0.4f, height = 10.dp, topPadding = 6.dp)
    }
}

@Composable
fun SkeletonBar(widthFraction: Float, height: Dp, topPadding: Dp = 0.dp) {
    Box(
        Modifier.padding(top = topPadding).fillMaxWidth(widthFraction).height(height)
            .clip(RoundedCornerShape(4.dp)).shimmer(),
    )
}

@Composable
fun SkeletonRow(count: Int = 7) {
    ShimmerHost {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(count) { SkeletonCard() }
        }
    }
}

@Composable
fun SkeletonGrid(columns: Int = 5, rows: Int = 2) {
    ShimmerHost {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(rows) { SkeletonRow(columns) }
        }
    }
}
