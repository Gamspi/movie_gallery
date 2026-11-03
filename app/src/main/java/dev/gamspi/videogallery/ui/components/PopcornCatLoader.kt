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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
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
    val cream = remember { lerp(AppColors.Accent, Color.White, 0.72f) }
    val earColor = remember { lerp(AppColors.Accent, AppColors.OnAccent, 0.46f) }
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
                drawPath(shapes.bucket, AppColors.Accent)
                drawPath(shapes.stripes, cream)
                drawPath(shapes.bucket, AppColors.OnAccent, style = outline)
                drawPath(shapes.popcornHeap, cream)
                drawPath(shapes.popcornHeap, AppColors.OnAccent, style = outline)
                drawPath(shapes.rim, cream)
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

// Fixed 160 x 160 artwork, allocated once for each loader's lifetime.
private class PopcornCatShapes {
    val tail = Path().apply {
        moveTo(106f, 128f)
        cubicTo(125f, 137f, 132f, 122f, 127f, 109f)
        cubicTo(121f, 93f, 133f, 80f, 143f, 87f)
        cubicTo(151f, 94f, 141f, 102f, 137f, 97f)
        cubicTo(130f, 105f, 146f, 119f, 134f, 135f)
        cubicTo(126f, 145f, 114f, 142f, 106f, 138f)
        close()
    }
    val body = Path().apply {
        moveTo(57f, 84f)
        cubicTo(47f, 92f, 44f, 117f, 49f, 134f)
        cubicTo(55f, 147f, 106f, 147f, 113f, 134f)
        cubicTo(119f, 114f, 110f, 92f, 101f, 84f)
        close()
    }
    val feet = Path().apply {
        addOval(Rect(38f, 124f, 65f, 145f))
        addOval(Rect(98f, 124f, 123f, 145f))
    }
    val head = Path().apply {
        moveTo(43f, 52f)
        cubicTo(41f, 42f, 41f, 28f, 46f, 25f)
        cubicTo(49f, 24f, 60f, 33f, 66f, 38f)
        cubicTo(75f, 35f, 86f, 35f, 95f, 38f)
        cubicTo(101f, 32f, 111f, 24f, 115f, 25f)
        cubicTo(120f, 30f, 119f, 45f, 118f, 53f)
        cubicTo(134f, 79f, 115f, 98f, 81f, 98f)
        cubicTo(47f, 98f, 28f, 79f, 43f, 52f)
        close()
    }
    val ears = Path().apply {
        moveTo(48f, 33f)
        lineTo(60f, 42f)
        lineTo(48f, 48f)
        close()
        moveTo(112f, 33f)
        lineTo(101f, 42f)
        lineTo(113f, 48f)
        close()
    }
    val eyes = Path().apply {
        moveTo(54f, 67f)
        cubicTo(57f, 59f, 67f, 59f, 71f, 67f)
        moveTo(89f, 67f)
        cubicTo(93f, 59f, 103f, 59f, 107f, 67f)
    }
    val muzzle = Path().apply {
        addOval(Rect(65f, 73f, 81f, 88f))
        addOval(Rect(80f, 73f, 96f, 88f))
    }
    val nose = Path().apply {
        moveTo(76f, 72f)
        quadraticTo(80f, 70f, 85f, 72f)
        quadraticTo(85f, 74f, 81f, 78f)
        quadraticTo(79f, 77f, 76f, 73f)
        close()
    }
    val smile = Path().apply {
        moveTo(76f, 87f)
        quadraticTo(80f, 91f, 85f, 87f)
    }
    val whiskers = Path().apply {
        moveTo(45f, 76f)
        lineTo(57f, 78f)
        moveTo(46f, 84f)
        lineTo(57f, 82f)
        moveTo(104f, 78f)
        lineTo(116f, 76f)
        moveTo(104f, 82f)
        lineTo(115f, 84f)
    }
    val bucket = Path().apply {
        moveTo(56f, 107f)
        lineTo(106f, 107f)
        lineTo(100f, 141f)
        quadraticTo(81f, 147f, 62f, 141f)
        close()
    }
    val stripes = Path().apply {
        moveTo(63f, 108f)
        lineTo(71f, 108f)
        lineTo(74f, 143.5f)
        lineTo(67f, 142.5f)
        close()
        moveTo(91f, 108f)
        lineTo(99f, 108f)
        lineTo(95f, 142.5f)
        lineTo(88f, 143.5f)
        close()
    }
    val popcornHeap = Path().apply {
        moveTo(56f, 105f)
        cubicTo(52f, 100f, 55f, 94f, 61f, 94f)
        cubicTo(61f, 91f, 71f, 91f, 74f, 97f)
        cubicTo(74f, 89f, 89f, 89f, 90f, 97f)
        cubicTo(97f, 92f, 104f, 92f, 104f, 98f)
        cubicTo(112f, 98f, 111f, 105f, 106f, 107f)
        close()
    }
    val rim = Path().apply {
        moveTo(56f, 103f)
        lineTo(106f, 103f)
        cubicTo(112f, 103f, 112f, 111f, 106f, 111f)
        lineTo(56f, 111f)
        cubicTo(50f, 111f, 50f, 103f, 56f, 103f)
        close()
    }
    val paws = Path().apply {
        moveTo(50f, 105f)
        cubicTo(41f, 106f, 43f, 124f, 54f, 125f)
        cubicTo(66f, 126f, 64f, 116f, 57f, 113f)
        moveTo(111f, 105f)
        cubicTo(120f, 106f, 118f, 124f, 107f, 125f)
        cubicTo(95f, 126f, 97f, 116f, 104f, 113f)
    }
    val kernel = Path().apply {
        moveTo(-5f, 4f)
        cubicTo(-9f, 1f, -6f, -4f, -3f, -4f)
        cubicTo(-3f, -10f, 5f, -10f, 5f, -4f)
        cubicTo(11f, -3f, 10f, 4f, 5f, 5f)
        cubicTo(2f, 8f, -2f, 7f, -5f, 4f)
        close()
    }
}
