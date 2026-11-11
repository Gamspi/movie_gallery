package dev.gamspi.videogallery.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerKeysTest {
    private val unset = Long.MIN_VALUE + 1

    private fun key(
        k: RemoteKey,
        focus: PlayerFocus,
        pos: Long = 30_000,
        dur: Long = 100_000,
        shown: Boolean = false,
    ) = handleKey(k, pos, dur, focus, shown)

    @Test fun none_table() {
        val f = PlayerFocus.None
        assertEquals(PlayerCommand.TogglePlay, key(RemoteKey.Center, f))
        assertEquals(PlayerCommand.TogglePlay, key(RemoteKey.PlayPause, f))
        assertEquals(PlayerCommand.SeekTo(20_000), key(RemoteKey.Left, f))
        assertEquals(PlayerCommand.SeekTo(40_000), key(RemoteKey.Right, f))
        assertEquals(PlayerCommand.FocusTrack, key(RemoteKey.Down, f))
        assertEquals(PlayerCommand.PassToFocus, key(RemoteKey.Up, f))
        assertEquals(PlayerCommand.Exit, key(RemoteKey.Back, f))
    }

    @Test fun none_backWithShownPanelHidesIt() {
        assertEquals(PlayerCommand.HidePanel, key(RemoteKey.Back, PlayerFocus.None, shown = true))
        assertEquals(PlayerCommand.Exit, key(RemoteKey.Back, PlayerFocus.None, shown = false))
    }

    @Test fun track_table() {
        val f = PlayerFocus.Track
        assertEquals(PlayerCommand.TogglePlay, key(RemoteKey.Center, f))
        assertEquals(PlayerCommand.TogglePlay, key(RemoteKey.PlayPause, f))
        assertEquals(PlayerCommand.SeekTo(20_000), key(RemoteKey.Left, f))
        assertEquals(PlayerCommand.SeekTo(40_000), key(RemoteKey.Right, f))
        assertEquals(PlayerCommand.FocusButtons, key(RemoteKey.Down, f))
        assertEquals(PlayerCommand.HidePanel, key(RemoteKey.Up, f))
        assertEquals(PlayerCommand.HidePanel, key(RemoteKey.Back, f, shown = true))
    }

    @Test fun trackSeekClamps() {
        val f = PlayerFocus.Track
        assertEquals(PlayerCommand.SeekTo(0), key(RemoteKey.Left, f, pos = 4_000))
        assertEquals(PlayerCommand.SeekTo(100_000), key(RemoteKey.Right, f, pos = 95_000))
    }

    @Test fun buttons_table() {
        val f = PlayerFocus.Buttons
        assertEquals(PlayerCommand.PassToFocus, key(RemoteKey.Center, f))
        assertEquals(PlayerCommand.TogglePlay, key(RemoteKey.PlayPause, f))
        assertEquals(PlayerCommand.PassToFocus, key(RemoteKey.Left, f))
        assertEquals(PlayerCommand.PassToFocus, key(RemoteKey.Right, f))
        assertEquals(PlayerCommand.PassToFocus, key(RemoteKey.Down, f))
        assertEquals(PlayerCommand.FocusTrack, key(RemoteKey.Up, f))
        assertEquals(PlayerCommand.HidePanel, key(RemoteKey.Back, f, shown = true))
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
        assertEquals(PlayerCommand.CloseMenu, key(RemoteKey.Back, f, shown = true))
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
        assertEquals(PlayerCommand.BackToSections, key(RemoteKey.Back, f, shown = true))
    }

    @Test fun menuLayer_table() {
        val f = PlayerFocus.MenuLayer
        assertEquals(PlayerCommand.PassToFocus, key(RemoteKey.Center, f))
        assertEquals(PlayerCommand.TogglePlay, key(RemoteKey.PlayPause, f))
        assertEquals(PlayerCommand.PassToFocus, key(RemoteKey.Left, f))
        assertEquals(PlayerCommand.PassToFocus, key(RemoteKey.Right, f))
        assertEquals(PlayerCommand.PassToFocus, key(RemoteKey.Down, f))
        assertEquals(PlayerCommand.PassToFocus, key(RemoteKey.Up, f))
        assertEquals(PlayerCommand.BackToLayers, key(RemoteKey.Back, f))
        assertEquals(PlayerCommand.BackToLayers, key(RemoteKey.Back, f, shown = true))
        assertFalse(shouldAutoHidePanel(playing = true, focus = f, dialogOpen = false))
    }

    @Test fun seekClampsToZeroAndDuration() {
        val f = PlayerFocus.None
        assertEquals(PlayerCommand.SeekTo(0), key(RemoteKey.Left, f, pos = 4_000))
        assertEquals(PlayerCommand.SeekTo(100_000), key(RemoteKey.Right, f, pos = 95_000))
        assertEquals(PlayerCommand.SeekTo(40_000), key(RemoteKey.Right, f, dur = unset))
    }

    @Test fun downRepeatDoesNotOpenMenu() =
        assertEquals(PlayerCommand.PassToFocus, key(RemoteKey.Down, PlayerFocus.Buttons))

    @Test fun autoHide_whilePlayingOnRootTrackOrButtons() {
        for (f in listOf(PlayerFocus.None, PlayerFocus.Track, PlayerFocus.Buttons)) {
            assertTrue(shouldAutoHidePanel(playing = true, focus = f, dialogOpen = false))
        }
    }

    @Test fun autoHide_notWhenPausedMenuOrDialog() {
        for (f in PlayerFocus.entries) {
            assertFalse(shouldAutoHidePanel(playing = false, focus = f, dialogOpen = false))
            assertFalse(shouldAutoHidePanel(playing = true, focus = f, dialogOpen = true))
        }
        assertFalse(shouldAutoHidePanel(playing = true, focus = PlayerFocus.MenuSections, dialogOpen = false))
        assertFalse(shouldAutoHidePanel(playing = true, focus = PlayerFocus.MenuSection, dialogOpen = false))
    }
}
