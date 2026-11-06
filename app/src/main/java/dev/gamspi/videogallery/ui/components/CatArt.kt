package dev.gamspi.videogallery.ui.components

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import dev.gamspi.videogallery.ui.theme.AppColors

// Mascot palette shared by every cat drawing.
internal object CatColors {
    val BucketWhite = Color(0xFFFFFFFF)
    val BucketRed = Color(0xFFE53935)
    val BucketBlue = Color(0xFF1E5BD8)
    val Cream = lerp(AppColors.Accent, Color.White, 0.72f)
    val Ear = lerp(AppColors.Accent, AppColors.OnAccent, 0.46f)
}

// Fixed 160 x 160 artwork shared by the loader and the mascot; allocated once per composable.
internal class PopcornCatShapes {
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
    val stripesRed = Path().apply {
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
    val stripeBlue = Path().apply {
        moveTo(76.5f, 108f)
        lineTo(85.5f, 108f)
        lineTo(84.5f, 144.5f)
        lineTo(77.5f, 144.5f)
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
