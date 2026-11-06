package dev.gamspi.videogallery.settings

import dev.gamspi.videogallery.BuildConfig

/** Maps SecretStore keys to raw values; blank values are omitted. */
internal fun buildEnvDefaults(
    kinopoisk: String,
    tmdb: String,
    poiskkino: String,
    osKey: String,
    osUser: String,
    osPassword: String,
    proxyUrl: String,
    proxyKey: String,
    rutrackerUser: String,
    rutrackerPassword: String,
): Map<String, String> = mapOf(
    SettingsRepository.KEY_KINOPOISK to kinopoisk,
    SettingsRepository.KEY_TMDB to tmdb,
    SettingsRepository.KEY_POISKKINO to poiskkino,
    SettingsRepository.KEY_OS_KEY to osKey,
    SettingsRepository.KEY_OS_USER to osUser,
    SettingsRepository.KEY_OS_PASSWORD to osPassword,
    SettingsRepository.KEY_PROXY_URL to proxyUrl,
    SettingsRepository.KEY_PROXY_KEY to proxyKey,
    SettingsRepository.KEY_RUTRACKER_USER to rutrackerUser,
    SettingsRepository.KEY_RUTRACKER_PASSWORD to rutrackerPassword,
).filterValues { it.isNotBlank() }

/** Fallback defaults baked from the root .env at build time. */
internal fun envDefaults(): Map<String, String> = buildEnvDefaults(
    kinopoisk = BuildConfig.ENV_KINOPOISK_API_KEY,
    tmdb = BuildConfig.ENV_TMDB_API_KEY,
    poiskkino = BuildConfig.ENV_POISKKINO_API_KEY,
    osKey = BuildConfig.ENV_OPENSUBTITLES_API_KEY,
    osUser = BuildConfig.ENV_OPENSUBTITLES_USERNAME,
    osPassword = BuildConfig.ENV_OPENSUBTITLES_PASSWORD,
    proxyUrl = BuildConfig.ENV_PROXY_URL,
    proxyKey = BuildConfig.ENV_PROXY_KEY,
    rutrackerUser = BuildConfig.ENV_RUTRACKER_USERNAME,
    rutrackerPassword = BuildConfig.ENV_RUTRACKER_PASSWORD,
)
