package dev.gamspi.videogallery.player

enum class RemoteKey { Center, PlayPause, Left, Right, Down, Back }

sealed interface PlayerCommand {
    data object TogglePlay : PlayerCommand
    data class SeekTo(val ms: Long) : PlayerCommand
    data object OpenAudioMenu : PlayerCommand
    data object CloseMenu : PlayerCommand
    data object Exit : PlayerCommand
}

private const val SEEK_STEP_MS = 10_000L

fun handleKey(key: RemoteKey, positionMs: Long, durationMs: Long, menuOpen: Boolean): PlayerCommand =
    when (key) {
        RemoteKey.Center, RemoteKey.PlayPause -> PlayerCommand.TogglePlay
        RemoteKey.Left -> PlayerCommand.SeekTo((positionMs - SEEK_STEP_MS).coerceAtLeast(0))
        RemoteKey.Right -> {
            val target = positionMs + SEEK_STEP_MS
            PlayerCommand.SeekTo(if (durationMs > 0) target.coerceAtMost(durationMs) else target)
        }
        RemoteKey.Down -> PlayerCommand.OpenAudioMenu
        RemoteKey.Back -> if (menuOpen) PlayerCommand.CloseMenu else PlayerCommand.Exit
    }
