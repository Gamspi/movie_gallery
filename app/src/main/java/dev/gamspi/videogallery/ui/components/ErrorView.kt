package dev.gamspi.videogallery.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
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
) {
    val message = catalogErrorMessage(kind, LocalCatalogSource.current)
    val needsSettings = kind == ErrorKind.MissingKey || kind == ErrorKind.BadKey
    val focus = remember { FocusRequester() }
    if (autoFocus) LaunchedEffect(kind) { runCatching { focus.requestFocus() } }
    Column(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, style = MaterialTheme.typography.headlineMedium)
        Button(
            onClick = if (needsSettings) onOpenSettings else onRetry,
            modifier = Modifier.focusRequester(focus),
        ) {
            Text(if (needsSettings) "Настройки" else "Повторить")
        }
    }
}
