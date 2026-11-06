package dev.gamspi.videogallery.ui.components

import java.util.Locale

/** Rating badge text with one decimal, e.g. "7.8"; null for absent or non-positive ratings. */
fun formatRating(rating: Double?): String? =
    rating?.takeIf { it > 0 && !it.isNaN() && !it.isInfinite() }?.let { String.format(Locale.ROOT, "%.1f", it) }

/** Progress shown on a card: only a started and unfinished share in (0, 1). */
fun visibleProgress(progress: Float?): Float? = progress?.takeIf { it > 0f && it < 1f }
