package dev.gamspi.videogallery.catalog

sealed class CatalogException : Exception() {
    data object MissingApiKey : CatalogException()
    data object Unauthorized : CatalogException()
    data object Network : CatalogException()
    data class Http(val code: Int) : CatalogException()
}
