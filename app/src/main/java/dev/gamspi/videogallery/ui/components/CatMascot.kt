package dev.gamspi.videogallery.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
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

enum class CatPose { Shrug, Sad, Wave }

@Composable
fun CatMascot(pose: CatPose, modifier: Modifier = Modifier, size: Dp = 140.dp, message: String? = null) {
    val base = remember { PopcornCatShapes() }
    val extra = remember { CatPoseShapes() }
    val ink = remember { CatInk() }
    val transition = rememberInfiniteTransition(label = "CatMascot")
    // Pose motion: shoulder bob, breathing or paw wave.
    val motion = transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(
                when (pose) {
                    CatPose.Shrug -> 700
                    CatPose.Sad -> 2400
                    CatPose.Wave -> 420
                },
                easing = FastOutSlowInEasing,
            ),
            RepeatMode.Reverse,
        ),
        label = "Motion",
    )
    val sway = transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(if (pose == CatPose.Sad) 2600 else 1300, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "Tail",
    )
    val description = when (pose) {
        CatPose.Shrug -> "Котик разводит лапами"
        CatPose.Sad -> "Грустный котик"
        CatPose.Wave -> "Котик машет лапой"
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Canvas(Modifier.size(size).semantics { contentDescription = description }) {
            // Read animation state only in draw; geometry and strokes are cached above.
            val m = motion.value
            val s = sway.value
            val viewportScale = minOf(this.size.width, this.size.height) / 160f
            withTransform({
                translate(
                    (this@Canvas.size.width - 160f * viewportScale) / 2f,
                    (this@Canvas.size.height - 160f * viewportScale) / 2f,
                )
                scale(viewportScale, viewportScale, pivot = Offset.Zero)
            }) {
                when (pose) {
                    CatPose.Shrug -> drawShrug(base, extra, ink, m, s)
                    CatPose.Sad -> drawSad(base, extra, ink, m, s)
                    CatPose.Wave -> drawWave(base, extra, ink, m, s)
                }
            }
        }
        if (message != null) {
            Text(
                text = message,
                modifier = Modifier.widthIn(max = 520.dp).padding(horizontal = 16.dp),
                color = CatColors.Cream,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// Static head-and-ears mark for headers; strokes are thicker to stay readable at 32dp.
@Composable
fun CatLogo(size: Dp = 32.dp) {
    val base = remember { PopcornCatShapes() }
    val outline = remember { Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round) }
    val features = remember { Stroke(width = 5f, cap = StrokeCap.Round) }
    Canvas(Modifier.size(size)) {
        val s = minOf(this.size.width, this.size.height) / 100f
        withTransform({
            translate((this@Canvas.size.width - 100f * s) / 2f, (this@Canvas.size.height - 100f * s) / 2f)
            scale(s, s, pivot = Offset.Zero)
            // Crop the 160 artwork to the 100 x 100 box around the head.
            translate(-31f, -11.5f)
        }) {
            drawPath(base.head, AppColors.Accent)
            drawPath(base.head, AppColors.OnAccent, style = outline)
            drawPath(base.ears, CatColors.Ear)
            drawPath(base.eyes, AppColors.OnAccent, style = features)
            drawPath(base.muzzle, CatColors.Cream)
            drawPath(base.smile, AppColors.OnAccent, style = outline)
            drawPath(base.nose, AppColors.OnAccent)
        }
    }
}

// Strokes shared by all poses.
private class CatInk {
    val outline = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val features = Stroke(width = 2.8f, cap = StrokeCap.Round)
    // Compensates the 0.55 scale of the ground bucket.
    val smallOutline = Stroke(width = 3.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    // Limbs drawn as thick strokes: dark border under a lighter core.
    val limbBorder = Stroke(width = 12f, cap = StrokeCap.Round)
    val limbCore = Stroke(width = 8f, cap = StrokeCap.Round)
}

private fun DrawScope.drawShadow() {
    drawOval(AppColors.OnAccent, topLeft = Offset(34f, 140f), size = Size(96f, 9f), alpha = 0.45f)
}

private fun DrawScope.drawTail(base: PopcornCatShapes, ink: CatInk, angle: Float) {
    rotate(angle, pivot = Offset(112f, 132f)) {
        drawPath(base.tail, AppColors.Accent)
        drawPath(base.tail, AppColors.OnAccent, style = ink.outline)
    }
}

private fun DrawScope.drawTorso(base: PopcornCatShapes, ink: CatInk) {
    drawPath(base.body, AppColors.Accent)
    drawPath(base.body, AppColors.OnAccent, style = ink.outline)
    drawPath(base.feet, AppColors.Accent)
    drawPath(base.feet, AppColors.OnAccent, style = ink.outline)
}

private fun DrawScope.drawHead(base: PopcornCatShapes, ink: CatInk) {
    drawPath(base.head, AppColors.Accent)
    drawPath(base.head, AppColors.OnAccent, style = ink.outline)
    drawPath(base.ears, CatColors.Ear)
}

private fun DrawScope.drawMuzzle(base: PopcornCatShapes, ink: CatInk, mouth: Path) {
    drawPath(base.muzzle, CatColors.Cream)
    drawPath(mouth, AppColors.OnAccent, style = ink.outline)
    drawPath(base.whiskers, AppColors.OnAccent, style = ink.outline)
    drawPath(base.nose, AppColors.OnAccent)
}

private fun DrawScope.drawBucket(base: PopcornCatShapes, outline: Stroke, full: Boolean, opening: Path? = null) {
    drawPath(base.bucket, CatColors.BucketWhite)
    drawPath(base.stripesRed, CatColors.BucketRed)
    drawPath(base.stripeBlue, CatColors.BucketBlue)
    drawPath(base.bucket, AppColors.OnAccent, style = outline)
    if (full) {
        drawPath(base.popcornHeap, CatColors.Cream)
        drawPath(base.popcornHeap, AppColors.OnAccent, style = outline)
    }
    drawPath(base.rim, CatColors.BucketWhite)
    drawPath(base.rim, AppColors.OnAccent, style = outline)
    if (opening != null) drawPath(opening, AppColors.OnAccent, alpha = 0.6f)
}

// Paws out to the sides, palms up; the full bucket stands on the ground.
private fun DrawScope.drawShrug(base: PopcornCatShapes, extra: CatPoseShapes, ink: CatInk, motion: Float, sway: Float) {
    drawShadow()
    drawOval(AppColors.OnAccent, topLeft = Offset(4f, 141f), size = Size(32f, 6f), alpha = 0.45f)
    drawTail(base, ink, -6f + 12f * sway)
    drawTorso(base, ink)
    withTransform({
        translate(-24.8f, 65.15f)
        scale(0.55f, 0.55f, pivot = Offset.Zero)
    }) {
        drawBucket(base, ink.smallOutline, full = true)
    }
    translate(top = -2.5f * motion) {
        drawPath(extra.shrugArms, AppColors.OnAccent, style = ink.limbBorder)
        drawPath(extra.shrugArms, AppColors.Accent, style = ink.limbCore)
        drawPath(extra.shrugPaws, AppColors.Accent)
        drawPath(extra.shrugPaws, AppColors.OnAccent, style = ink.outline)
        drawPath(extra.shrugPads, CatColors.Cream)
        drawHead(base, ink)
        drawPath(extra.dotEyes, AppColors.OnAccent)
        drawPath(extra.raisedBrows, AppColors.OnAccent, style = ink.outline)
        drawMuzzle(base, ink, extra.flatMouth)
    }
}

// Droopy ears, worried face and an empty bucket.
private fun DrawScope.drawSad(base: PopcornCatShapes, extra: CatPoseShapes, ink: CatInk, motion: Float, sway: Float) {
    drawShadow()
    drawTail(base, ink, 6f + 6f * sway)
    drawTorso(base, ink)
    translate(top = 2f + 1.5f * motion) {
        rotate(-28f, pivot = Offset(62f, 40f)) {
            drawPath(extra.earLeft, AppColors.Accent)
            drawPath(extra.earLeft, AppColors.OnAccent, style = ink.outline)
        }
        rotate(28f, pivot = Offset(99f, 40f)) {
            drawPath(extra.earRight, AppColors.Accent)
            drawPath(extra.earRight, AppColors.OnAccent, style = ink.outline)
        }
        drawPath(extra.headBase, AppColors.Accent)
        drawPath(extra.headBase, AppColors.OnAccent, style = ink.outline)
        drawPath(extra.droopyEarInner, CatColors.Ear)
        drawPath(extra.sadEyes, AppColors.OnAccent, style = ink.features)
        drawPath(extra.worriedBrows, AppColors.OnAccent, style = ink.outline)
        drawMuzzle(base, ink, extra.frown)
    }
    drawBucket(base, ink.outline, full = false, opening = extra.bucketOpening)
    drawPath(base.paws, AppColors.Accent)
    drawPath(base.paws, AppColors.OnAccent, style = ink.outline)
}

// Left paw waves, right paw keeps the full bucket.
private fun DrawScope.drawWave(base: PopcornCatShapes, extra: CatPoseShapes, ink: CatInk, motion: Float, sway: Float) {
    drawShadow()
    drawTail(base, ink, -9f + 18f * sway)
    drawTorso(base, ink)
    rotate(-12f + 24f * motion, pivot = Offset(54f, 96f)) {
        drawPath(extra.waveArm, AppColors.Accent)
        drawPath(extra.waveArm, AppColors.OnAccent, style = ink.outline)
        drawPath(extra.wavePad, CatColors.Cream)
    }
    drawHead(base, ink)
    drawPath(base.eyes, AppColors.OnAccent, style = ink.features)
    drawMuzzle(base, ink, base.smile)
    drawBucket(base, ink.outline, full = true)
    drawPath(extra.holdPaw, AppColors.Accent)
    drawPath(extra.holdPaw, AppColors.OnAccent, style = ink.outline)
}

// Pose-specific parts in the same 160 x 160 space as PopcornCatShapes.
private class CatPoseShapes {
    // Shrug: arm centerlines (stroked thick), palm-up paws and pads.
    val shrugArms = Path().apply {
        moveTo(60f, 96f)
        quadraticTo(34f, 106f, 25f, 80f)
        moveTo(101f, 96f)
        quadraticTo(127f, 106f, 136f, 80f)
    }
    val shrugPaws = Path().apply {
        addOval(Rect(15f, 73f, 33f, 83f))
        addOval(Rect(128f, 73f, 146f, 83f))
    }
    val shrugPads = Path().apply {
        addOval(Rect(18f, 74f, 30f, 79f))
        addOval(Rect(131f, 74f, 143f, 79f))
    }
    val dotEyes = Path().apply {
        addOval(Rect(59f, 60.5f, 66f, 67.5f))
        addOval(Rect(94.5f, 60.5f, 101.5f, 67.5f))
    }
    val raisedBrows = Path().apply {
        moveTo(56f, 53f)
        quadraticTo(62f, 49f, 68f, 52f)
        moveTo(93f, 52f)
        quadraticTo(99f, 49f, 105f, 53f)
    }
    val flatMouth = Path().apply {
        moveTo(76f, 89f)
        lineTo(85f, 89f)
    }

    // Sad: the head outline without ears, ears drawn separately and rotated down.
    val headBase = Path().apply {
        moveTo(43f, 52f)
        cubicTo(46f, 43f, 55f, 38f, 66f, 38f)
        cubicTo(75f, 35f, 86f, 35f, 95f, 38f)
        cubicTo(106f, 38f, 115f, 43f, 118f, 53f)
        cubicTo(134f, 79f, 115f, 98f, 81f, 98f)
        cubicTo(47f, 98f, 28f, 79f, 43f, 52f)
        close()
    }
    val earLeft = Path().apply {
        moveTo(43f, 52f)
        cubicTo(41f, 42f, 41f, 28f, 46f, 25f)
        cubicTo(49f, 24f, 60f, 33f, 66f, 38f)
        close()
    }
    val earRight = Path().apply {
        moveTo(95f, 38f)
        cubicTo(101f, 32f, 111f, 24f, 115f, 25f)
        cubicTo(120f, 30f, 119f, 45f, 118f, 53f)
        close()
    }
    // Inner ears in final (already drooped) positions, outside the head base.
    val droopyEarInner = Path().apply {
        moveTo(45f, 38f)
        lineTo(55f, 38.5f)
        lineTo(47f, 43f)
        close()
        moveTo(116f, 38f)
        lineTo(106f, 38.5f)
        lineTo(114f, 43f)
        close()
    }
    val sadEyes = Path().apply {
        moveTo(55f, 68f)
        quadraticTo(61f, 61f, 70f, 64f)
        moveTo(106f, 68f)
        quadraticTo(100f, 61f, 91f, 64f)
    }
    val worriedBrows = Path().apply {
        moveTo(56f, 57f)
        lineTo(67f, 53f)
        moveTo(105f, 57f)
        lineTo(94f, 53f)
    }
    val frown = Path().apply {
        moveTo(76f, 90f)
        quadraticTo(80.5f, 86f, 85f, 90f)
    }
    val bucketOpening = Path().apply {
        addOval(Rect(58f, 105f, 104f, 109.5f))
    }

    // Wave: raised left arm (open path: the fill closes it, the outline does not).
    val waveArm = Path().apply {
        moveTo(62f, 96f)
        cubicTo(49f, 88f, 37f, 74f, 30f, 62f)
        cubicTo(27f, 52f, 13f, 52f, 14f, 62f)
        cubicTo(15f, 70f, 29f, 86f, 52f, 104f)
    }
    val wavePad = Path().apply {
        addOval(Rect(17.5f, 55.5f, 25.5f, 61.5f))
    }
    val holdPaw = Path().apply {
        moveTo(111f, 105f)
        cubicTo(120f, 106f, 118f, 124f, 107f, 125f)
        cubicTo(95f, 126f, 97f, 116f, 104f, 113f)
    }
}
