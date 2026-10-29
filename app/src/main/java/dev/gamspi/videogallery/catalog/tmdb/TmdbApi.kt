package dev.gamspi.videogallery.catalog.tmdb

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface TmdbApi {
    @GET("movie/popular")
    suspend fun popular(@Query("page") page: Int): MovieListDto

    @GET("genre/movie/list")
    suspend fun genres(): GenreListDto

    @GET("discover/movie")
    suspend fun discover(
        @Query("with_genres") genreId: Int,
        @Query("page") page: Int,
        @Query("sort_by") sortBy: String = "popularity.desc",
    ): MovieListDto

    @GET("search/movie")
    suspend fun search(@Query("query") query: String): MovieListDto

    @GET("movie/{id}")
    suspend fun details(
        @Path("id") id: Int,
        @Query("append_to_response") append: String = "external_ids",
    ): MovieDetailsDto
}
