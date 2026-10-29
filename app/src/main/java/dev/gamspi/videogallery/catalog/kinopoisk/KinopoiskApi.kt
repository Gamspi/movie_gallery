package dev.gamspi.videogallery.catalog.kinopoisk

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface KinopoiskApi {
    @GET("api/v2.2/films/collections")
    suspend fun popular(
        @Query("page") page: Int,
        @Query("type") type: String = "TOP_POPULAR_MOVIES",
    ): FilmListDto

    @GET("api/v2.2/films/filters")
    suspend fun filters(): FiltersDto

    @GET("api/v2.2/films")
    suspend fun byGenre(
        @Query("genres") genreId: Int,
        @Query("page") page: Int,
        @Query("order") order: String = "NUM_VOTE",
        @Query("type") type: String = "FILM",
    ): FilmListDto

    @GET("api/v2.2/films")
    suspend fun search(
        @Query("keyword") keyword: String,
        @Query("page") page: Int = 1,
        @Query("order") order: String = "NUM_VOTE",
        @Query("type") type: String = "FILM",
    ): FilmListDto

    @GET("api/v2.2/films/{id}")
    suspend fun details(@Path("id") id: Int): FilmDetailsDto
}
