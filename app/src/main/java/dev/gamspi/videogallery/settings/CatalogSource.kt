package dev.gamspi.videogallery.settings

enum class CatalogSource { Kinopoisk, Tmdb }

data class ActiveCatalog(val source: CatalogSource, val apiKey: String?)
