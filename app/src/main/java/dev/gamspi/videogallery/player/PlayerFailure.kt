package dev.gamspi.videogallery.player

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException

data class PlayerFailure(
    val message: String,
    val retryable: Boolean = false,
    val canChooseAudio: Boolean = false,
)

/** Uses structured codes only; exception text may contain credentials or source URLs. */
fun PlaybackException.toPlayerFailure(): PlayerFailure {
    val mime = (this as? ExoPlaybackException)?.rendererFormat?.sampleMimeType
    val status = generateSequence(cause) { it.cause }.take(10)
        .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
    return playbackFailure(errorCode, mime, status)
}

internal fun playbackFailure(errorCode: Int, rendererMime: String? = null, httpStatus: Int? = null): PlayerFailure =
    when (errorCode) {
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> when {
            rendererMime?.startsWith("audio/") == true -> PlayerFailure(
                "Не удалось декодировать звук. Выберите другую аудиодорожку или раздачу.", canChooseAudio = true,
            )
            rendererMime?.startsWith("video/") == true -> PlayerFailure(
                "Не удалось декодировать видео. Выберите раздачу с другим видеокодеком.",
            )
            else -> PlayerFailure("Не удалось декодировать этот формат. Выберите другую раздачу.")
        }
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED -> PlayerFailure(
            "Не удалось воспроизвести звук. Выберите другую аудиодорожку или раздачу.", canChooseAudio = true,
        )
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_TIMEOUT -> PlayerFailure("Не удалось загрузить видео. Проверьте соединение и повторите попытку.", retryable = true)
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> when (httpStatus) {
            401, 403 -> PlayerFailure("Источник не разрешает доступ к видео. Выберите другую раздачу.")
            404, 410 -> PlayerFailure("Видео больше недоступно по этому адресу. Выберите другую раздачу.")
            429, in 500..599 -> PlayerFailure("Источник временно недоступен. Попробуйте ещё раз.", retryable = true)
            else -> PlayerFailure("Источник не отдал видео. Выберите другую раздачу.")
        }
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> PlayerFailure("Видеофайл не найден. Выберите другую раздачу.")
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> PlayerFailure("Нет доступа к видеофайлу. Выберите другой источник.")
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> PlayerFailure("Этот адрес не разрешён. Используйте HTTPS или другой источник.")
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> PlayerFailure(
            "Не удалось прочитать формат видео. Файл повреждён или не поддерживается; выберите другую раздачу.",
        )
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> PlayerFailure("Не удалось прочитать видео. Попробуйте ещё раз.", retryable = true)
        in 6000..6999 -> PlayerFailure("Не удалось открыть защищённое видео. Выберите другой источник.")
        else -> PlayerFailure("Не удалось воспроизвести видео. Выберите другую раздачу.")
    }

fun audioRecoveryOptions(tracks: List<AudioTrack>, selected: Pair<Int, Int>?): List<AudioTrack> =
    tracks.filter { it.supported && (it.group to it.index) != selected }
