package app.pocketpilot.ui

import android.app.Activity
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import java.util.WeakHashMap

/** Keeps the window out of screenshots and the recents preview while [secure] is true. */
@Composable
internal fun SecureWindow(secure: Boolean) {
    val window = (LocalContext.current as? Activity)?.window
    DisposableEffect(window, secure) {
        if (secure) window?.let(SecureFlag::acquire)
        onDispose { if (secure) window?.let(SecureFlag::release) }
    }
}

/**
 * FLAG_SECURE is one flag on the window, so a screen leaving must not clear it while another secure
 * screen in the same window is still showing. Counts the holders per window; runs on the main thread.
 */
private object SecureFlag {
    private val holders = WeakHashMap<Window, Int>()

    fun acquire(window: Window) {
        holders[window] = (holders[window] ?: 0) + 1
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun release(window: Window) {
        val remaining = (holders[window] ?: 1) - 1
        if (remaining > 0) {
            holders[window] = remaining
        } else {
            holders.remove(window)
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
