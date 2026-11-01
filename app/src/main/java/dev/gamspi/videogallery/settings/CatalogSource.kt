package dev.gamspi.videogallery.settings

enum class CatalogSource { PoiskKino, Kinopoisk, Tmdb }

data class ActiveCatalog(val source: CatalogSource, val apiKey: String?)
