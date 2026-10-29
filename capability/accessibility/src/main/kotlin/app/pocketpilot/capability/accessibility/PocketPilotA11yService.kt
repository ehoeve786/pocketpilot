package app.pocketpilot.capability.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * The Accessibility service the owner turns on in system settings. It holds no logic: it hands itself
 * and its events to [A11yBridge], where the READ_UI, INJECT_INPUT and CAPTURE_SCREEN backends find it.
 */
class PocketPilotA11yService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())

    /**
     * Pulling the shade down raises no event that reliably reaches us, so the shade is checked on a timer while
     * the service runs, as well as on each window event.
     */
    private val shadeCheck =
        object : Runnable {
            override fun run() {
                // Looking for the shade costs system calls, so it is only done while floating buttons are on screen.
                if (A11yOverlay.buttonsShown() > 0) hideButtonsWhileShadeIsDown()
                main.postDelayed(this, SHADE_CHECK_INTERVAL_MS)
            }
        }

    override fun onServiceConnected() {
        super.onServiceConnected()
        A11yBridge.attach(this)
        main.post(shadeCheck)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        A11yBridge.onEvent(event)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED || event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            hideButtonsWhileShadeIsDown()
        }
    }

    /** The floating buttons step aside while the notification shade is pulled down. */
    private fun hideButtonsWhileShadeIsDown() {
        A11yOverlay.setButtonsHidden(shadeIsDown())
    }

    /**
     * The shade is not listed as an accessibility window on the test phones, and system windows have no titles,
     * so it is found in the System UI tree instead: when System UI is the active window and the shade's views are visible.
     */
    private fun shadeIsDown(): Boolean {
        val root = rootInActiveWindow ?: return false
        try {
            if (root.packageName?.toString() != SYSTEM_UI_PACKAGE) return false
            return SHADE_VIEW_IDS.any { id -> anyVisible(root.findAccessibilityNodeInfosByViewId(id)) }
        } finally {
            recycle(root)
        }
    }

    /** True when any of [nodes] is on screen. Every node is handed back to the system. */
    private fun anyVisible(nodes: List<AccessibilityNodeInfo>): Boolean {
        val visible = nodes.any { it.isVisibleToUser }
        nodes.forEach { recycle(it) }
        return visible
    }

    /** Hands a node back to the system. Before Android 13 nodes are pooled and must be recycled; later versions do it themselves. */
    @Suppress("DEPRECATION")
    private fun recycle(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) node.recycle()
    }

    /** Key presses are only observed, never consumed, so they reach the phone as usual. */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        A11yBridge.onKey(event)
        return false
    }

    override fun onInterrupt() = Unit

    private companion object {
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"

        /** Views that only exist on the open notification shade, by their full resource ids. */
        val SHADE_VIEW_IDS = listOf("$SYSTEM_UI_PACKAGE:id/qs_container", "$SYSTEM_UI_PACKAGE:id/notification_stack_scroller")

        /** How often the shade is checked; short enough that the buttons step aside before a finger reaches them. */
        const val SHADE_CHECK_INTERVAL_MS = 250L
    }

    override fun onUnbind(intent: Intent?): Boolean {
        main.removeCallbacks(shadeCheck)
        A11yBridge.detach(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        main.removeCallbacks(shadeCheck)
        A11yBridge.detach(this)
        super.onDestroy()
    }
}
