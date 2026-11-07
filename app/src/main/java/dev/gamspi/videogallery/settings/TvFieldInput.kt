package dev.gamspi.videogallery.settings

import android.view.KeyEvent

/** What a D-pad text field does with a key-down that reached it. */
internal enum class TvFieldKeyAction { MoveUp, MoveDown, StartEdit, EndEdit, EndEditMoveUp, EndEditMoveDown, Pass }

/**
 * Key routing for [TvTextField]. While editing, a TV IME consumes D-pad and Back itself,
 * so such a key reaching the field means the IME is already hidden: leave edit mode.
 */
internal fun tvFieldKeyAction(editing: Boolean, keyCode: Int): TvFieldKeyAction = if (editing) {
    when (keyCode) {
        KeyEvent.KEYCODE_BACK -> TvFieldKeyAction.EndEdit
        KeyEvent.KEYCODE_DPAD_UP -> TvFieldKeyAction.EndEditMoveUp
        KeyEvent.KEYCODE_DPAD_DOWN -> TvFieldKeyAction.EndEditMoveDown
        else -> TvFieldKeyAction.Pass
    }
} else {
    when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> TvFieldKeyAction.MoveUp
        KeyEvent.KEYCODE_DPAD_DOWN -> TvFieldKeyAction.MoveDown
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> TvFieldKeyAction.StartEdit
        else -> TvFieldKeyAction.Pass
    }
}

/**
 * Detects the IME being dismissed during one edit session: reports true once the IME
 * was seen visible and then hidden. Hidden before the IME ever appeared is ignored.
 */
internal class ImeDismissTracker {
    private var seenVisible = false

    fun onVisibility(visible: Boolean): Boolean {
        if (visible) seenVisible = true
        return !visible && seenVisible
    }
}
