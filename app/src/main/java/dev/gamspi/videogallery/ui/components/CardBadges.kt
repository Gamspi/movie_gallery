package dev.gamspi.videogallery.ui.components

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
