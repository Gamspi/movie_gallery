package dev.gamspi.videogallery.settings

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvFieldInputTest {
    @Test
    fun notEditingMovesFocusAndStartsEditOnOk() {
        assertEquals(TvFieldKeyAction.MoveUp, tvFieldKeyAction(false, KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(TvFieldKeyAction.MoveDown, tvFieldKeyAction(false, KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(TvFieldKeyAction.StartEdit, tvFieldKeyAction(false, KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(TvFieldKeyAction.StartEdit, tvFieldKeyAction(false, KeyEvent.KEYCODE_ENTER))
        assertEquals(TvFieldKeyAction.StartEdit, tvFieldKeyAction(false, KeyEvent.KEYCODE_NUMPAD_ENTER))
    }

    @Test
    fun notEditingLetsBackReachScreenHandler() {
        assertEquals(TvFieldKeyAction.Pass, tvFieldKeyAction(false, KeyEvent.KEYCODE_BACK))
        assertEquals(TvFieldKeyAction.Pass, tvFieldKeyAction(false, KeyEvent.KEYCODE_DPAD_LEFT))
    }

    @Test
    fun editingEndsOnKeysTheImeDidNotConsume() {
        assertEquals(TvFieldKeyAction.EndEdit, tvFieldKeyAction(true, KeyEvent.KEYCODE_BACK))
        assertEquals(TvFieldKeyAction.EndEditMoveUp, tvFieldKeyAction(true, KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(TvFieldKeyAction.EndEditMoveDown, tvFieldKeyAction(true, KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(TvFieldKeyAction.Pass, tvFieldKeyAction(true, KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(TvFieldKeyAction.Pass, tvFieldKeyAction(true, KeyEvent.KEYCODE_A))
    }

    @Test
    fun imeTrackerIgnoresHiddenBeforeShown() {
        val t = ImeDismissTracker()
        assertFalse(t.onVisibility(false))
        assertFalse(t.onVisibility(false))
        assertFalse(t.onVisibility(true))
        assertFalse(t.onVisibility(true))
        assertTrue(t.onVisibility(false))
    }
}
