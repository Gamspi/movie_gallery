package dev.gamspi.videogallery.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerKeysTest {
    private val unset = Long.MIN_VALUE + 1

    private fun key(k: RemoteKey, focus: PlayerFocus, pos: Long = 30_000, dur: Long = 100_000) =
        handleKey(k, pos, dur, focus)

    @Test fun none_table() {
        val f = PlayerFocus.None
        assertEquals(PlayerCommand.TogglePlay, key(RemoteKey.Center, f))
        assertEquals(PlayerCommand.TogglePlay, key(RemoteKey.PlayPause, f))
        assertEquals(PlayerCommand.SeekTo(20_000), key(RemoteKey.Left, f))
        assertEquals(PlayerCommand.SeekTo(40_000), key(RemoteKey.Right, f))
        assertEquals(PlayerCommand.FocusButtons, key(RemoteKey.Down, f))
        assertEquals(PlayerCommand.PassToFocus, key(RemoteKey.Up, f))
        assertEquals(PlayerCommand.Exit, key(RemoteKey.Back, f))
    }

    @Test fun buttons_table() {
        val f = PlayerFocus.Buttons
        assertEquals(PlayerCommand.PassToFocus, key(RemoteKey.Center, f))
        assertEquals(PlayerCommand.TogglePlay, key(RemoteKey.PlayPause, f))
        assertEquals(PlayerCommand.PassToFocus, key(RemoteKey.Left, f))
        assertEquals(PlayerCommand.PassToFocus, key(RemoteKey.Right, f))
        assertEquals(PlayerCommand.PassToFocus, key(RemoteKey.Down, f))
        assertEquals(PlayerCommand.ReleaseButtons, key(RemoteKey.Up, f))
        assertEquals(PlayerCommand.ReleaseButtons, key(RemoteKey.Back, f))
    }

    @Test fun menuSections_table() {
        val f = PlayerFocus.MenuSections
        assertEquals(PlayerCommand.PassToFocus, key(RemoteKey.Center, f))
        assertEquals(PlayerCommand.TogglePlay, key(RemoteKey.PlayPause, f))
        assertEquals(PlayerCommand.PassToFocus, key(RemoteKey.Left, f))
        assertEquals(PlayerCommand.PassToFocus, key(RemoteKey.Right, f))
        assertEquals(PlayerCommand.PassToFocus, key(RemoteKey.Down, f))
        assertEquals(PlayerCommand.PassToFocus, key(RemoteKey.Up, f))
        assertEquals(PlayerCommand.CloseMenu, key(RemoteKey.Back, f))
    }

    @Test fun menuSection_table() {
        val f = PlayerFocus.MenuSection
        assertEquals(PlayerCommand.PassToFocus, key(RemoteKey.Center, f))
        assertEquals(PlayerCommand.TogglePlay, key(RemoteKey.PlayPause, f))
        assertEquals(PlayerCommand.PassToFocus, key(RemoteKey.Left, f))
        assertEquals(PlayerCommand.PassToFocus, key(RemoteKey.Right, f))
        assertEquals(PlayerCommand.PassToFocus, key(RemoteKey.Down, f))
        assertEquals(PlayerCommand.PassToFocus, key(RemoteKey.Up, f))
        assertEquals(PlayerCommand.BackToSections, key(RemoteKey.Back, f))
    }

    @Test fun seekClampsToZeroAndDuration() {
        val f = PlayerFocus.None
        assertEquals(PlayerCommand.SeekTo(0), key(RemoteKey.Left, f, pos = 4_000))
        assertEquals(PlayerCommand.SeekTo(100_000), key(RemoteKey.Right, f, pos = 95_000))
        assertEquals(PlayerCommand.SeekTo(40_000), key(RemoteKey.Right, f, dur = unset))
    }

    @Test fun downRepeatDoesNotOpenMenu() =
        assertEquals(PlayerCommand.PassToFocus, key(RemoteKey.Down, PlayerFocus.Buttons))
}
