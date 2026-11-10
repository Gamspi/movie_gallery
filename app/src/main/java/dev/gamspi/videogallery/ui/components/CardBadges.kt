package dev.gamspi.videogallery.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.gamspi.videogallery.catalog.model.comingSoonLabel
import dev.gamspi.videogallery.ui.theme.AppColors
import java.time.LocalDate
import java.util.Locale

/** Rating badge text with one decimal, e.g. "7.8"; null for absent or non-positive ratings. */
fun formatRating(rating: Double?): String? =
    rating?.takeIf { it > 0 && !it.isNaN() && !it.isInfinite() }?.let { String.format(Locale.ROOT, "%.1f", it) }

/** Progress shown on a card: only a started and unfinished share in (0, 1). */
fun visibleProgress(progress: Float?): Float? = progress?.takeIf { it > 0f && it < 1f }

/** Whether an episode has been watched to completion. */
fun watchedMark(progress: Float?): Boolean = progress != null && progress >= 1f

/** Smallest drawn share of the card progress bar, so a just-started movie still shows a mark. */
const val MIN_CARD_PROGRESS = 0.04f

/** Width share of the progress fill: the visible progress raised to [MIN_CARD_PROGRESS]. */
fun cardProgressFill(progress: Float?): Float? = visibleProgress(progress)?.coerceAtLeast(MIN_CARD_PROGRESS)

/** Badge text of a series card. */
const val SERIES_BADGE = "Сериал"

/** Shared bottom badge; compact list posters may wrap it instead of truncating the date. */
@Composable
fun ComingSoonBadge(premiereDate: String?, year: Int?, modifier: Modifier = Modifier) {
    comingSoonLabel(premiereDate, year, LocalDate.now())?.let { label ->
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = AppColors.OnAccent,
            modifier = modifier.background(AppColors.Accent, RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 3.dp),
        )
    }
}
