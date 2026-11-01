package dev.gamspi.videogallery.player

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

/**
 * Debug entry to the production screen; extras become SavedStateHandle defaults.
 * Launch media normally; send keyCode commands with FLAG_ACTIVITY_SINGLE_TOP and no URL.
 */
@AndroidEntryPoint
class PlayerProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VideoGalleryTheme {
                PlayerScreen(onBack = { finish() })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.i(TAG, "command keyCode=${intent.getIntExtra("keyCode", KeyEvent.KEYCODE_UNKNOWN)} " +
            "hasUrl=${intent.hasExtra("url")} lifecycle=${lifecycle.currentState}")
        if (!intent.hasExtra("keyCode") || intent.hasExtra("url")) return
        val keyCode = intent.getIntExtra("keyCode", KeyEvent.KEYCODE_UNKNOWN)
        if (keyCode !in PROBE_KEYS) return
        window.decorView.post {
            if (!isFinishing && !isDestroyed) {
                val decor = window.decorView
                val compose = decor.findComposeView()
                val requested = if (compose != null && !compose.hasFocus()) compose.requestFocus() else null
                Log.i(TAG, "dispatch keyCode=$keyCode windowFocus=${decor.hasWindowFocus()} " +
                    "focus=${decor.findFocus()?.javaClass?.simpleName} compose=${compose != null} requested=$requested")
                val down = dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                val up = dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
                Log.i(TAG, "dispatched keyCode=$keyCode down=$down up=$up")
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
        const val TAG = "PlayerProbe"
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
