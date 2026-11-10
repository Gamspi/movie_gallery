package dev.gamspi.videogallery.catalog.tmdb

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface TmdbApi {
    @GET("{type}/{id}/recommendations")
    suspend fun recommendations(@Path("type") type: String, @Path("id") id: Int): MovieListDto

    @GET("{type}/{id}/similar")
    suspend fun similar(@Path("type") type: String, @Path("id") id: Int): MovieListDto

    @GET("find/{imdb}")
    suspend fun findByImdb(@Path("imdb") imdb: String, @Query("external_source") source: String = "imdb_id"): FindDto

    @GET("discover/movie")
    suspend fun upcomingMovies(
        @Query("primary_release_date.gte") from: String,
        @Query("primary_release_date.lte") to: String,
        @Query("sort_by") sortBy: String = "popularity.desc",
    ): MovieListDto

    @GET("discover/tv")
    suspend fun upcomingSeries(
        @Query("first_air_date.gte") from: String,
        @Query("first_air_date.lte") to: String,
        @Query("sort_by") sortBy: String = "popularity.desc",
    ): MovieListDto

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

    @GET("tv/popular")
    suspend fun tvPopular(@Query("page") page: Int): MovieListDto

    @GET("genre/tv/list")
    suspend fun tvGenres(): GenreListDto

    @GET("discover/tv")
    suspend fun tvDiscover(
        @Query("with_genres") genreId: Int,
        @Query("page") page: Int,
        @Query("sort_by") sortBy: String = "popularity.desc",
    ): MovieListDto

    @GET("search/multi")
    suspend fun searchMulti(@Query("query") query: String): MovieListDto

    @GET("tv/{id}")
    suspend fun tvDetails(
        @Path("id") id: Int,
        @Query("append_to_response") append: String = "external_ids",
    ): MovieDetailsDto

    @GET("tv/{id}/season/{number}")
    suspend fun season(@Path("id") id: Int, @Path("number") number: Int): SeasonDto
}
