package app.pocketpilot.privileged

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.pocketpilot.PocketPilotApplication
import app.pocketpilot.R
import app.pocketpilot.capability.accessibility.A11yBridge
import kotlinx.coroutines.launch

/**
 * Reads the code and port from Settings' "Pair device with pairing code" dialog with PocketPilot's Accessibility
 * service and pairs on its own, so the owner types nothing (as the Shizuku fork does on TVs). It watches only
 * Settings, only for [WATCH_MS] after the owner taps Pair, and stops once paired.
 */
internal object PairingCodeWatcher {
    private val SETTINGS = setOf("com.android.settings", "com.google.android.settings")
    private val CODE = Regex("^\\d{6}$")

    // An address and port as the dialog shows it: 192.168.8.100:37611, or from Android 17 an mDNS host name such as
    // Android_976T3PGI.local:37813.
    private val ADDRESS = Regex("[A-Za-z0-9_.\\-\\]]+:(\\d{4,5})$")
    private const val WATCH_MS = 3 * 60 * 1000L
    private const val READ_EVERY_MS = 300L

    private val main = Handler(Looper.getMainLooper())

    /** Code and port pairs already tried, so a wrong or expired code is not sent again. */
    private val tried = mutableSetOf<String>()

    @Volatile
    private var pairing = false

    private var lastRead = 0L

    /** Whether Accessibility is on, so the code can be read rather than typed. */
    fun available(): Boolean = A11yBridge.connected.value

    fun start(context: Context) {
        val app = context.applicationContext as PocketPilotApplication
        main.removeCallbacksAndMessages(null)
        tried.clear()
        pairing = false
        A11yBridge.listenToWindows { pkg -> if (pkg in SETTINGS) read(app) }
        main.postDelayed(::stop, WATCH_MS)
    }

    fun stop() {
        A11yBridge.listenToWindows(null)
        main.removeCallbacksAndMessages(null)
    }

    /** Called on the main thread for each change in Settings' windows. */
    private fun read(app: PocketPilotApplication) {
        val now = SystemClock.uptimeMillis()
        if (pairing || now - lastRead < READ_EVERY_MS) return
        lastRead = now
        val texts = A11yBridge.textsIn(SETTINGS).map(String::trim)
        val codeAt = texts.indexOfFirst { CODE.matches(it) }
        if (codeAt < 0) return
        // The dialog shows its own address and port next to the code; the screen behind it shows the connect port.
        val port =
            texts.drop(codeAt).firstNotNullOfOrNull { ADDRESS.find(it)?.groupValues?.get(1) }
                ?: texts.firstNotNullOfOrNull { ADDRESS.find(it)?.groupValues?.get(1) }
                ?: return
        val input = "${texts[codeAt]} $port"
        if (!tried.add(input)) return
        pairing = true
        PairingNotifier.done(app, app.getString(R.string.builtin_pairing))
        app.appScope.launch {
            val result = app.privileged.pair(input)
            pairing = false
            if (result.isSuccess) {
                main.post(::stop)
                PairingNotifier.done(app, app.getString(R.string.builtin_paired))
            } else {
                val reason = result.exceptionOrNull()?.message.orEmpty()
                PairingNotifier.ask(app, app.getString(R.string.builtin_pair_failed, reason))
            }
        }
    }
}
