package dev.gamspi.videogallery.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.gamspi.videogallery.ui.icons.AppIcons
import dev.gamspi.videogallery.ui.theme.AppColors

/** Vertical list of settings sections; focus returns to the section the user came back from. */
@Composable
fun SettingsHomeScreen(onOpenSection: (SettingsSection) -> Unit) {
    var lastName by rememberSaveable { mutableStateOf(SettingsSection.entries.first().name) }
    val requesters = remember { SettingsSection.entries.associateWith { FocusRequester() } }
    LaunchedEffect(Unit) {
        val target = SettingsSection.entries.firstOrNull { it.name == lastName } ?: SettingsSection.entries.first()
        runCatching { requesters.getValue(target).requestFocus() }
    }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Настройки", style = MaterialTheme.typography.headlineMedium)
        SettingsSection.entries.forEach { section ->
            ListItem(
                selected = false,
                onClick = {
                    lastName = section.name
                    onOpenSection(section)
                },
                headlineContent = { Text(section.title) },
                trailingContent = { Icon(AppIcons.ChevronRight, contentDescription = null) },
                colors = ListItemDefaults.colors(
                    focusedContainerColor = AppColors.Accent,
                    focusedContentColor = AppColors.OnAccent,
                ),
                modifier = Modifier.focusRequester(requesters.getValue(section)),
            )
        }
    }
}
