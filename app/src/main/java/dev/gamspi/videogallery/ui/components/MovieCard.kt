package dev.gamspi.videogallery.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import dev.gamspi.videogallery.catalog.model.Movie

@Composable
fun MovieCard(movie: Movie, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.width(150.dp)) {
        Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            val poster = Modifier.fillMaxWidth().aspectRatio(2f / 3f)
            if (movie.posterUrl != null) {
                // Shimmer shows behind the poster until Coil delivers a result.
                var loading by remember(movie.posterUrl) { mutableStateOf(true) }
                AsyncImage(
                    model = movie.posterUrl,
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
        Text(
            movie.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        movie.year?.let { Text(it.toString(), style = MaterialTheme.typography.labelSmall) }
    }
}
