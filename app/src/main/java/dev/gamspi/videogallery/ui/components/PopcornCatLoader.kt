package dev.gamspi.videogallery.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.gamspi.videogallery.ui.theme.AppColors

@Composable
fun PopcornCatLoader(modifier: Modifier = Modifier, size: Dp = 160.dp, status: String? = null) {
    val shapes = remember { PopcornCatShapes() }
    val cream = CatColors.Cream
    val earColor = CatColors.Ear
    val outline = remember { Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round) }
    val features = remember { Stroke(width = 2.8f, cap = StrokeCap.Round) }
    val transition = rememberInfiniteTransition(label = "PopcornCat")
    val chew = transition.animateFloat(
        initialValue = 0f, targetValue = 1.8f,
        animationSpec = infiniteRepeatable(tween(260, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "Cheeks",
    )
    val tail = transition.animateFloat(
        initialValue = -9f, targetValue = 9f,
        animationSpec = infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "Tail",
    )
    val popcorn = transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing)),
        label = "Popcorn",
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Canvas(
            modifier = Modifier.size(size).semantics { contentDescription = "Загрузка" },
        ) {
            // Read animation state only in draw; geometry and strokes are cached above.
            val jawOffset = chew.value
            val tailAngle = tail.value
            val cycle = popcorn.value
            val viewportScale = minOf(this.size.width, this.size.height) / 160f
            withTransform({
                translate(
                    (this@Canvas.size.width - 160f * viewportScale) / 2f,
                    (this@Canvas.size.height - 160f * viewportScale) / 2f,
                )
                scale(viewportScale, viewportScale, pivot = Offset.Zero)
            }) {
                drawOval(AppColors.OnAccent, topLeft = Offset(34f, 140f), size = Size(96f, 9f), alpha = 0.45f)
                rotate(tailAngle, pivot = Offset(112f, 132f)) {
                    drawPath(shapes.tail, AppColors.Accent)
                    drawPath(shapes.tail, AppColors.OnAccent, style = outline)
                }
                drawPath(shapes.body, AppColors.Accent)
                drawPath(shapes.body, AppColors.OnAccent, style = outline)
                drawPath(shapes.feet, AppColors.Accent)
                drawPath(shapes.feet, AppColors.OnAccent, style = outline)
                drawPath(shapes.head, AppColors.Accent)
                drawPath(shapes.head, AppColors.OnAccent, style = outline)
                drawPath(shapes.ears, earColor)
                drawPath(shapes.eyes, AppColors.OnAccent, style = features)
                translate(top = jawOffset) {
                    drawPath(shapes.muzzle, cream)
                    drawPath(shapes.smile, AppColors.OnAccent, style = outline)
                    drawPath(shapes.whiskers, AppColors.OnAccent, style = outline)
                }
                drawPath(shapes.nose, AppColors.OnAccent)
                drawPath(shapes.bucket, CatColors.BucketWhite)
                drawPath(shapes.stripesRed, CatColors.BucketRed)
                drawPath(shapes.stripeBlue, CatColors.BucketBlue)
                drawPath(shapes.bucket, AppColors.OnAccent, style = outline)
                drawPath(shapes.popcornHeap, cream)
                drawPath(shapes.popcornHeap, AppColors.OnAccent, style = outline)
                drawPath(shapes.rim, CatColors.BucketWhite)
                drawPath(shapes.rim, AppColors.OnAccent, style = outline)
                drawPath(shapes.paws, AppColors.Accent)
                drawPath(shapes.paws, AppColors.OnAccent, style = outline)

                // Three short flights, followed by a quiet interval before the next loop.
                for (index in 0 until 3) {
                    val flight = (cycle - (0.12f + index * 0.16f)) / 0.24f
                    if (flight > 0f && flight < 1f) {
                        val direction = if (index == 1) 1f else -1f
                        val x = 76f + index * 6f + direction * 49f * flight
                        val y = 101f - 54f * 4f * flight * (1f - flight) + 14f * flight
                        val opacity = minOf(flight / 0.12f, (1f - flight) / 0.35f, 1f)
                        translate(left = x, top = y) {
                            rotate(direction * flight * 150f, pivot = Offset.Zero) {
                                drawPath(shapes.kernel, cream, alpha = opacity)
                                drawPath(shapes.kernel, earColor, alpha = opacity, style = outline)
                            }
                        }
                    }
                }
            }
        }
        if (status != null) {
            Text(
                text = status,
                modifier = Modifier.widthIn(max = 360.dp).padding(horizontal = 16.dp),
                color = cream,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun FullScreenLoader(status: String? = null) {
    Box(
        modifier = Modifier.fillMaxSize().background(AppColors.Background),
        contentAlignment = Alignment.Center,
    ) {
        PopcornCatLoader(modifier = Modifier.padding(24.dp), status = status)
    }
}
