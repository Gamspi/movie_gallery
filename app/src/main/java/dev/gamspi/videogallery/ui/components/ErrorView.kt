package dev.gamspi.videogallery.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.gamspi.videogallery.catalog.CatalogException
import dev.gamspi.videogallery.settings.CatalogSource

enum class ErrorKind { MissingKey, BadKey, QuotaExceeded, Network, Other }

fun CatalogException.toErrorKind(): ErrorKind = when (this) {
    CatalogException.MissingApiKey -> ErrorKind.MissingKey
    CatalogException.Unauthorized -> ErrorKind.BadKey
    CatalogException.Network -> ErrorKind.Network
    CatalogException.QuotaExceeded -> ErrorKind.QuotaExceeded
    CatalogException.Parse, is CatalogException.Http -> ErrorKind.Other
}

fun catalogErrorMessage(kind: ErrorKind, source: CatalogSource): String {
    val provider = when (source) {
        CatalogSource.PoiskKino -> "PoiskKino"
        CatalogSource.Kinopoisk -> "Кинопоиска"
        CatalogSource.Tmdb -> "TMDB"
    }
    return when (kind) {
        ErrorKind.MissingKey -> "Нужен ключ $provider"
        ErrorKind.BadKey -> "Ключ $provider неверный"
        ErrorKind.QuotaExceeded -> "Лимит запросов на сегодня исчерпан"
        ErrorKind.Network -> "Нет сети"
        ErrorKind.Other -> "Ошибка загрузки"
    }
}

@Composable
fun ErrorView(
    kind: ErrorKind,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    autoFocus: Boolean = true,
    catSize: Dp = 120.dp,
) {
    val message = catalogErrorMessage(kind, LocalCatalogSource.current)
    val needsSettings = kind == ErrorKind.MissingKey || kind == ErrorKind.BadKey
    val retryFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }
    val focus = if (needsSettings) settingsFocus else retryFocus
    if (autoFocus) LaunchedEffect(kind) { runCatching { focus.requestFocus() } }
    Column(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CatMascot(CatPose.Sad, size = catSize)
        Text(message, style = MaterialTheme.typography.headlineMedium)
        // Settings stay reachable for every error (e.g. offline at launch).
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AccentButton(onClick = onRetry, modifier = Modifier.focusRequester(retryFocus)) { Text("Повторить") }
            AccentButton(onClick = onOpenSettings, modifier = Modifier.focusRequester(settingsFocus)) { Text("Настройки") }
        }
    }
}
