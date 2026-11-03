package dev.gamspi.videogallery.player

enum class RemoteKey { Center, PlayPause, Left, Right, Up, Down, Back }

/** Where remote focus currently lives; decides who owns each key. */
enum class PlayerFocus { None, Buttons, MenuSections, MenuSection }

sealed interface PlayerCommand {
    data object TogglePlay : PlayerCommand
    data class SeekTo(val ms: Long) : PlayerCommand
    data object FocusButtons : PlayerCommand
    data object ReleaseButtons : PlayerCommand
    data object OpenMenuSection : PlayerCommand
    data object BackToSections : PlayerCommand
    data object CloseMenu : PlayerCommand
    data object Exit : PlayerCommand

    /** The focused element (buttons/menu) handles the key; the root must not consume it. */
    data object PassToFocus : PlayerCommand
}

private const val SEEK_STEP_MS = 10_000L

fun handleKey(key: RemoteKey, positionMs: Long, durationMs: Long, focus: PlayerFocus): PlayerCommand {
    if (key == RemoteKey.PlayPause) return PlayerCommand.TogglePlay
    return when (focus) {
        PlayerFocus.None -> when (key) {
            RemoteKey.Center -> PlayerCommand.TogglePlay
            RemoteKey.Left -> PlayerCommand.SeekTo((positionMs - SEEK_STEP_MS).coerceAtLeast(0))
            RemoteKey.Right -> {
                val target = positionMs + SEEK_STEP_MS
                PlayerCommand.SeekTo(if (durationMs > 0) target.coerceAtMost(durationMs) else target)
            }
            RemoteKey.Down -> PlayerCommand.FocusButtons
            RemoteKey.Up -> PlayerCommand.PassToFocus
            RemoteKey.Back -> PlayerCommand.Exit
            RemoteKey.PlayPause -> PlayerCommand.TogglePlay
        }
        PlayerFocus.Buttons -> when (key) {
            RemoteKey.Up, RemoteKey.Back -> PlayerCommand.ReleaseButtons
            else -> PlayerCommand.PassToFocus
        }
        PlayerFocus.MenuSections ->
            if (key == RemoteKey.Back) PlayerCommand.CloseMenu else PlayerCommand.PassToFocus
        PlayerFocus.MenuSection ->
            if (key == RemoteKey.Back) PlayerCommand.BackToSections else PlayerCommand.PassToFocus
    }
}
