package dev.gamspi.videogallery.settings

import dev.gamspi.videogallery.torrents.ProviderId

enum class SettingsSection(val route: String, val title: String) {
    Catalog("catalog", "Каталог"),
    Trackers("trackers", "Трекеры"),
    Storage("storage", "Хранение"),
    Subtitles("subtitles", "Субтитры"),
    Translation("translation", "Перевод"),
    Network("network", "Сеть"),
    Keys("keys", "Ключи"),
    Debug("debug", "Отладка"),
}

const val SETTINGS_HOME_ROUTE = "settings"
const val SETTINGS_SECTION_ROUTE = "settings/{section}"

fun settingsRoute(section: SettingsSection): String = "settings/${section.route}"

fun sectionOf(route: String?): SettingsSection? = SettingsSection.entries.firstOrNull { it.route == route }

/** Sections whose edits are written when the user leaves with Back. */
val SettingsSection.autosaves: Boolean
    get() = this == SettingsSection.Catalog || this == SettingsSection.Trackers

/** Services listed in «Ключи»; each opens its own screen. Tracker titles follow [ProviderId.label]. */
enum class KeyService(val route: String, val title: String, val tracker: ProviderId? = null) {
    PoiskKino("poiskkino", "PoiskKino"),
    Kinopoisk("kinopoisk", "Кинопоиск"),
    Tmdb("tmdb", "TMDB"),
    Rutor("rutor", ProviderId.Rutor.label, ProviderId.Rutor),
    Apibay("apibay", ProviderId.Apibay.label, ProviderId.Apibay),
    Yts("yts", ProviderId.Yts.label, ProviderId.Yts),
    Rutracker("rutracker", ProviderId.Rutracker.label, ProviderId.Rutracker),
    OpenSubtitles("opensubtitles", "OpenSubtitles"),
    Proxy("proxy", "Прокси"),
}

const val KEY_SERVICE_ROUTE = "settings/keys/{service}"

fun keyServiceRoute(service: KeyService): String = "settings/keys/${service.route}"

fun keyServiceOf(route: String?): KeyService? = KeyService.entries.firstOrNull { it.route == route }
