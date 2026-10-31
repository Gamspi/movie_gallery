package dev.gamspi.videogallery.subs

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

interface OpenSubtitlesApi {
    @GET("subtitles")
    suspend fun search(
        @Query("moviehash") hash: String?,
        @Query("imdb_id") imdbId: String?,
        @Query("languages") languages: String,
    ): SearchResponseDto

    @POST("download")
    suspend fun download(
        @Body body: DownloadRequestDto,
        @Header("Authorization") auth: String?,
    ): DownloadResponseDto

    @POST("login")
    suspend fun login(@Body body: LoginRequestDto): LoginResponseDto
}

@Serializable
data class SearchResponseDto(val data: List<SubtitleItemDto> = emptyList())

@Serializable
data class SubtitleItemDto(val attributes: SubtitleAttributesDto)

@Serializable
data class SubtitleAttributesDto(
    val language: String? = null,
    val release: String? = null,
    @SerialName("download_count") val downloadCount: Int = 0,
    @SerialName("moviehash_match") val moviehashMatch: Boolean = false,
    val files: List<SubtitleFileDto> = emptyList(),
)

@Serializable
data class SubtitleFileDto(@SerialName("file_id") val fileId: Long)

@Serializable
data class DownloadRequestDto(@SerialName("file_id") val fileId: Long)

@Serializable
data class DownloadResponseDto(val link: String? = null, val remaining: Int? = null)

@Serializable
data class LoginRequestDto(val username: String, val password: String)

@Serializable
data class LoginResponseDto(val token: String? = null)
