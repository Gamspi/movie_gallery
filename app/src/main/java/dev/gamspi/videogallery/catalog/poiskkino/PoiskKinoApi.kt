package dev.gamspi.videogallery.catalog.poiskkino

import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query

interface PoiskKinoApi {
    @GET("v1.5/movie")
    suspend fun movies(
        @Header("X-API-KEY") key: String,
        @Query("next") next: String? = null,
        @Query("lists") list: String? = null,
        @Query("genres.id") genreId: Int? = null,
        @Query("type") type: String? = "movie",
        @Query("isSeries") isSeries: Boolean? = null,
        @Query("sortField") sortField: String = "votes.kp",
        @Query("sortType") sortType: String = "-1",
        @Query("limit") limit: Int = 20,
        @Query("premiere.world") premiereWorld: String? = null,
    ): MovieCursorDto

    @GET("v1.5/dictionary/genres")
    suspend fun genres(@Header("X-API-KEY") key: String): DictionaryDto

    @GET("v1.5/movie/search")
    suspend fun search(
        @Header("X-API-KEY") key: String,
        @Query("query") query: String,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 20,
    ): MovieSearchDto

    /**
     * Season endpoint. The API version (v1.4) is unverified against the PoiskKino documentation;
     * the response shape (docs[].episodes[]) follows the kinopoisk.dev v1.4 contract.
     */
    @GET("v1.4/season")
    suspend fun season(
        @Header("X-API-KEY") key: String,
        @Query("movieId") movieId: Int,
        @Query("number") number: Int,
        @Query("limit") limit: Int = 10,
    ): SeasonCursorDto

    @GET("v1.5/movie/{id}")
    suspend fun details(@Header("X-API-KEY") key: String, @Path("id") id: Int): MovieDto
}
