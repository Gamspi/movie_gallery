package dev.gamspi.videogallery.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.gamspi.videogallery.catalog.model.Movie
import dev.gamspi.videogallery.ui.theme.AppColors

private val CardShape = RoundedCornerShape(8.dp)
private val FocusBorder = Border(BorderStroke(3.dp, AppColors.Accent), shape = CardShape)

// Poster decode size: 150x225 dp card at TV density ~2.0.
private const val POSTER_WIDTH_PX = 300
private const val POSTER_HEIGHT_PX = 450

@Composable
fun MovieCard(movie: Movie, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.width(150.dp)) {
        Card(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            shape = CardDefaults.shape(CardShape),
            scale = CardDefaults.scale(focusedScale = 1.05f),
            border = CardDefaults.border(focusedBorder = FocusBorder, pressedBorder = FocusBorder),
        ) {
            val poster = Modifier.fillMaxWidth().aspectRatio(2f / 3f)
            val url = movie.posterUrl
            if (url != null) {
                val context = LocalContext.current
                // Decode at card size, no crossfade: keeps row scrolling cheap.
                val request = remember(url) {
                    ImageRequest.Builder(context)
                        .data(url)
                        .size(POSTER_WIDTH_PX, POSTER_HEIGHT_PX)
                        .crossfade(false)
                        .build()
                }
                // Shimmer shows behind the poster until Coil delivers a result.
                var loading by remember(url) { mutableStateOf(true) }
                AsyncImage(
                    model = request,
                    contentDescription = movie.title,
                    contentScale = ContentScale.Crop,
                    onState = { loading = it is AsyncImagePainter.State.Loading || it is AsyncImagePainter.State.Empty },
                    modifier = if (loading) poster.shimmer() else poster,
                )
            } else {
                Box(
                    modifier = poster.background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(movie.title, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        // Fixed-height captions below the 1.05 focus scale, so focus never shifts the layout.
        Text(
            movie.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 12.dp).height(20.dp),
        )
        Text(
            movie.year?.toString().orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.height(16.dp),
        )
    }
}
