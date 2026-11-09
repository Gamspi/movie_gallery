package dev.gamspi.videogallery.watchlist

import dev.gamspi.videogallery.catalog.model.MediaKind
import dev.gamspi.videogallery.settings.CatalogSource

data class SavedTarget(val source: CatalogSource, val id: Int, val kind: MediaKind)

fun savedTarget(row: ListRecord): SavedTarget? {
    val source = CatalogSource.entries.firstOrNull { it.name == row.text("catalog_source") } ?: return null
    val id = row.text("catalog_id").toIntOrNull()?.takeIf { it > 0 } ?: return null
    val series = row.text("type") in setOf("tv_show", "animated_series")
    return SavedTarget(source, id, if (series) MediaKind.Series else MediaKind.Movie)
}
