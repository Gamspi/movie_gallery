package dev.gamspi.videogallery.player

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerFailureTest {
    @Test fun audio_decoder_failure_offers_track_change_without_retrying_same_decoder() {
        val failure = playbackFailure(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, "audio/vnd.dts")
        assertTrue(failure.canChooseAudio)
        assertFalse(failure.retryable)
        assertTrue(failure.message.contains("звук"))
    }

    @Test fun video_decoder_failure_does_not_offer_audio_recovery() {
        val failure = playbackFailure(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, "video/hevc")
        assertFalse(failure.canChooseAudio)
        assertFalse(failure.retryable)
        assertTrue(failure.message.contains("видео"))
    }

    @Test fun decoder_failure_without_format_does_not_guess_renderer_type() {
        val failure = playbackFailure(PlaybackException.ERROR_CODE_DECODING_FAILED)
        assertFalse(failure.canChooseAudio)
        assertFalse(failure.retryable)
        assertTrue(failure.message.contains("формат"))
    }

    @Test fun audio_output_failure_can_recover_with_another_track() {
        val failure = playbackFailure(PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED)
        assertTrue(failure.canChooseAudio)
        assertFalse(failure.retryable)
    }

    @Test fun network_and_timeout_errors_allow_explicit_retry() {
        for (code in listOf(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, PlaybackException.ERROR_CODE_TIMEOUT)) {
            val failure = playbackFailure(code)
            assertTrue(failure.retryable)
            assertFalse(failure.canChooseAudio)
        }
    }

    @Test fun http_auth_and_missing_file_do_not_offer_meaningless_retry() {
        for (status in listOf(401, 403, 404, 410)) {
            assertFalse(playbackFailure(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, httpStatus = status).retryable)
        }
        assertTrue(playbackFailure(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, httpStatus = 403).message.contains("доступ"))
    }

    @Test fun transient_http_statuses_allow_retry_without_exposing_server_details() {
        for (status in listOf(429, 500, 503, 599)) {
            val failure = playbackFailure(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, httpStatus = status)
            assertTrue(failure.retryable)
            assertFalse(failure.message.contains(status.toString()))
        }
        assertFalse(playbackFailure(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, httpStatus = 400).retryable)
    }

    @Test fun malformed_container_is_distinct_from_network_or_codec() {
        val failure = playbackFailure(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)
        assertFalse(failure.retryable)
        assertFalse(failure.canChooseAudio)
        assertTrue(failure.message.contains("повреждён"))
    }

    @Test fun unknown_error_has_safe_russian_message() {
        val failure = playbackFailure(Int.MAX_VALUE, "audio/secret-query-data", 999)
        assertFalse(failure.retryable)
        assertFalse(failure.canChooseAudio)
        assertEquals("Не удалось воспроизвести видео. Выберите другую раздачу.", failure.message)
    }

    @Test fun protected_content_does_not_offer_audio_or_network_retry() {
        val failure = playbackFailure(PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED)
        assertFalse(failure.retryable)
        assertFalse(failure.canChooseAudio)
        assertTrue(failure.message.contains("защищённое"))
    }

    @Test fun recovery_options_exclude_failed_selection_and_unsupported_tracks() {
        val failed = AudioTrack(0, 0, "en", "DTS", "dts", true)
        val unsupported = AudioTrack(1, 0, "en", "Unknown", null, false)
        val alternative = AudioTrack(2, 0, "ru", "AAC", "aac", true)
        assertEquals(listOf(alternative), audioRecoveryOptions(listOf(failed, unsupported, alternative), 0 to 0))
        assertTrue(audioRecoveryOptions(listOf(failed, unsupported), 0 to 0).isEmpty())
    }
}
