package dev.gamspi.videogallery.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import dev.gamspi.videogallery.settings.LayerStyle

@Composable
fun SubtitleOverlay(
    textA: String?,
    textB: String?,
    styleA: LayerStyle,
    styleB: LayerStyle,
    liftDp: Dp,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.padding(start = 32.dp, end = 32.dp, bottom = 48.dp + liftDp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SubtitleLayer(textA, styleA)
            SubtitleLayer(textB, styleB)
        }
    }
}

@Composable
private fun SubtitleLayer(text: String?, style: LayerStyle) {
    if (text.isNullOrBlank()) return
    Text(
        text = text,
        color = Color(style.color.argb),
        fontSize = style.size.sp.sp,
        lineHeight = (style.size.sp * 1.2f).sp,
        textAlign = TextAlign.Center,
        maxLines = 3,
        style = TextStyle(shadow = Shadow(Color.Black, Offset(1f, 1f), 4f)),
    )
}
