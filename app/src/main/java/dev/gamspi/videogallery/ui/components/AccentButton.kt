package dev.gamspi.videogallery.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import dev.gamspi.videogallery.ui.theme.AppColors

/** Button with the amber focus accent; [selected] keeps a dim amber tint while unfocused. */
@Composable
fun AccentButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val base = if (selected) {
        ButtonDefaults.colors(
            containerColor = SelectedContainer,
            contentColor = AppColors.Accent,
            focusedContainerColor = AppColors.Accent,
            focusedContentColor = AppColors.OnAccent,
            pressedContainerColor = AppColors.Accent,
            pressedContentColor = AppColors.OnAccent,
        )
    } else {
        ButtonDefaults.colors(
            focusedContainerColor = AppColors.Accent,
            focusedContentColor = AppColors.OnAccent,
            pressedContainerColor = AppColors.Accent,
            pressedContentColor = AppColors.OnAccent,
        )
    }
    Button(onClick = onClick, onLongClick = onLongClick, modifier = modifier, enabled = enabled, colors = base, content = content)
}

private val SelectedContainer = Color(0xFF4A3A10)
