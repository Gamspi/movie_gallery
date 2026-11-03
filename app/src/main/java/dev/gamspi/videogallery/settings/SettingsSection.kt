package dev.gamspi.videogallery.settings

enum class SettingsSection(val route: String, val title: String) {
    Catalog("catalog", "Каталог"),
    Trackers("trackers", "Трекеры"),
    Storage("storage", "Хранение"),
    Subtitles("subtitles", "Субтитры"),
    Translation("translation", "Перевод"),
    OpenSubtitles("opensubtitles", "OpenSubtitles"),
    Debug("debug", "Отладка"),
}

const val SETTINGS_HOME_ROUTE = "settings"
const val SETTINGS_SECTION_ROUTE = "settings/{section}"

fun settingsRoute(section: SettingsSection): String = "settings/${section.route}"

fun sectionOf(route: String?): SettingsSection? = SettingsSection.entries.firstOrNull { it.route == route }

/** Sections whose edits are written when the user leaves with Back. */
val SettingsSection.autosaves: Boolean
    get() = this == SettingsSection.Catalog || this == SettingsSection.Trackers || this == SettingsSection.OpenSubtitles
