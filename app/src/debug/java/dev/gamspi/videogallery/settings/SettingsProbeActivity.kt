package dev.gamspi.videogallery.settings

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dagger.hilt.android.AndroidEntryPoint
import dev.gamspi.videogallery.ui.theme.VideoGalleryTheme

/** Debug-only local D-pad driver for the production settings screen. */
@AndroidEntryPoint
class SettingsProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VideoGalleryTheme {
                SettingsScreen(onBack = { finish() })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val keyCode = intent.getIntExtra("keyCode", KeyEvent.KEYCODE_UNKNOWN)
        if (keyCode !in PROBE_KEYS) return
        window.decorView.post {
            if (!isFinishing && !isDestroyed) {
                val compose = window.decorView.findComposeView()
                if (compose != null && !compose.hasFocus()) compose.requestFocus()
                val down = dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                val up = dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
                Log.i("SettingsProbe", "keyCode=$keyCode down=$down up=$up")
            }
        }
    }

    private fun View.findComposeView(): View? {
        if (javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") return this
        if (this is ViewGroup) for (index in 0 until childCount) {
            getChildAt(index).findComposeView()?.let { return it }
        }
        return null
    }

    private companion object {
        val PROBE_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_BACK,
        )
    }
}
