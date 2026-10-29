package dev.gamspi.videogallery.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    var text by remember { mutableStateOf(viewModel.tmdbApiKey.value.orEmpty()) }
    Column(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Настройки", style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Ключ TMDB API") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Получить: themoviedb.org → Settings → API")
        Button(onClick = {
            viewModel.save(text)
            onBack()
        }) { Text("Сохранить") }
    }
}
