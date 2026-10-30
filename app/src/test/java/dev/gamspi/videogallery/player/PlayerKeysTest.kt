package dev.gamspi.videogallery.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerKeysTest {
    private val unset = Long.MIN_VALUE + 1

    @Test fun center_toggles() =
        assertEquals(PlayerCommand.TogglePlay, handleKey(RemoteKey.Center, 0, 1000, false))

    @Test fun playPause_toggles() =
        assertEquals(PlayerCommand.TogglePlay, handleKey(RemoteKey.PlayPause, 0, 1000, false))

    @Test fun left_seeksBack10s() =
        assertEquals(PlayerCommand.SeekTo(20_000), handleKey(RemoteKey.Left, 30_000, 100_000, false))

    @Test fun left_clampsAtZero() =
        assertEquals(PlayerCommand.SeekTo(0), handleKey(RemoteKey.Left, 4_000, 100_000, false))

    @Test fun right_seeksForward10s() =
        assertEquals(PlayerCommand.SeekTo(40_000), handleKey(RemoteKey.Right, 30_000, 100_000, false))

    @Test fun right_clampsAtDuration() =
        assertEquals(PlayerCommand.SeekTo(100_000), handleKey(RemoteKey.Right, 95_000, 100_000, false))

    @Test fun right_unknownDuration_unclamped() =
        assertEquals(PlayerCommand.SeekTo(40_000), handleKey(RemoteKey.Right, 30_000, unset, false))

    @Test fun down_opensMenu() =
        assertEquals(PlayerCommand.OpenAudioMenu, handleKey(RemoteKey.Down, 0, 1000, false))

    @Test fun back_menuOpen_closes() =
        assertEquals(PlayerCommand.CloseMenu, handleKey(RemoteKey.Back, 0, 1000, true))

    @Test fun back_menuClosed_exits() =
        assertEquals(PlayerCommand.Exit, handleKey(RemoteKey.Back, 0, 1000, false))
}
