package dev.gamspi.videogallery.player

enum class RemoteKey { Center, PlayPause, Left, Right, Up, Down, Back }

/** Where remote focus currently lives; decides who owns each key. */
enum class PlayerFocus { None, Track, Buttons, MenuSections, MenuSection }

sealed interface PlayerCommand {
    data object TogglePlay : PlayerCommand
    data class SeekTo(val ms: Long) : PlayerCommand
    data object FocusTrack : PlayerCommand
    data object FocusButtons : PlayerCommand

    /** Hides the bottom panel and returns focus to the root. */
    data object HidePanel : PlayerCommand
    data object OpenMenuSection : PlayerCommand
    data object BackToSections : PlayerCommand
    data object CloseMenu : PlayerCommand
    data object Exit : PlayerCommand

    /** The focused element (buttons/menu) handles the key; the root must not consume it. */
    data object PassToFocus : PlayerCommand
}

private const val SEEK_STEP_MS = 10_000L

private fun seek(key: RemoteKey, positionMs: Long, durationMs: Long): PlayerCommand =
    if (key == RemoteKey.Left) {
        PlayerCommand.SeekTo((positionMs - SEEK_STEP_MS).coerceAtLeast(0))
    } else {
        val target = positionMs + SEEK_STEP_MS
        PlayerCommand.SeekTo(if (durationMs > 0) target.coerceAtMost(durationMs) else target)
    }

/** [panelShown] tells whether the bottom panel is visible, so Back hides it instead of exiting. */
fun handleKey(
    key: RemoteKey,
    positionMs: Long,
    durationMs: Long,
    focus: PlayerFocus,
    panelShown: Boolean = false,
): PlayerCommand {
    if (key == RemoteKey.PlayPause) return PlayerCommand.TogglePlay
    return when (focus) {
        PlayerFocus.None -> when (key) {
            RemoteKey.Center, RemoteKey.PlayPause -> PlayerCommand.TogglePlay
            RemoteKey.Left, RemoteKey.Right -> seek(key, positionMs, durationMs)
            RemoteKey.Down -> PlayerCommand.FocusTrack
            RemoteKey.Up -> PlayerCommand.PassToFocus
            RemoteKey.Back -> if (panelShown) PlayerCommand.HidePanel else PlayerCommand.Exit
        }
        PlayerFocus.Track -> when (key) {
            RemoteKey.Center, RemoteKey.PlayPause -> PlayerCommand.TogglePlay
            RemoteKey.Left, RemoteKey.Right -> seek(key, positionMs, durationMs)
            RemoteKey.Down -> PlayerCommand.FocusButtons
            RemoteKey.Up, RemoteKey.Back -> PlayerCommand.HidePanel
        }
        PlayerFocus.Buttons -> when (key) {
            RemoteKey.Up -> PlayerCommand.FocusTrack
            RemoteKey.Back -> PlayerCommand.HidePanel
            else -> PlayerCommand.PassToFocus
        }
        PlayerFocus.MenuSections ->
            if (key == RemoteKey.Back) PlayerCommand.CloseMenu else PlayerCommand.PassToFocus
        PlayerFocus.MenuSection ->
            if (key == RemoteKey.Back) PlayerCommand.BackToSections else PlayerCommand.PassToFocus
    }
}

/**
 * The panel hides after idle only while playing with focus on the root, track or buttons;
 * pause, the open menu and dialogs keep it.
 */
fun shouldAutoHidePanel(playing: Boolean, focus: PlayerFocus, dialogOpen: Boolean): Boolean =
    playing && !dialogOpen &&
        (focus == PlayerFocus.None || focus == PlayerFocus.Track || focus == PlayerFocus.Buttons)
