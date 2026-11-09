package app.pocketpilot.capability.accessibility

import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Floating texts a rule shows over other apps, each under its own identifier, drawn as Accessibility overlays like the
 * floating buttons. A text can be dragged, tapped and long-pressed; its words can change while it shows.
 */
object A11yFloatingText {
    /** How one text looks and behaves. Colours are ARGB; positions and width are percentages of the screen. */
    data class Style(
        val text: String,
        val textColor: Int,
        val background: Int,
        val textSizeSp: Float,
        val paddingDp: Int,
        val cornersDp: Int,
        val opacity: Float,
        /** [Gravity.START], [Gravity.CENTER_HORIZONTAL] or [Gravity.END]. */
        val align: Int,
        val xPercent: Int,
        val yPercent: Int,
        val maxWidthPercent: Int,
        val movable: Boolean,
        val onTap: (() -> Unit)?,
        val onLongPress: (() -> Unit)?,
    )

    private class Shown(
        val view: TextView,
        val params: WindowManager.LayoutParams,
        val owner: PocketPilotA11yService,
        var style: Style,
    )

    private val main = Handler(Looper.getMainLooper())
    private val shown = HashMap<String, Shown>()

    /**
     * Shows [style] as text [id], or changes the text already shown under [id], which keeps its place. Returns false
     * when the Accessibility service is off, so nothing can be shown.
     */
    fun show(
        id: String,
        style: Style,
    ): Boolean {
        val service = A11yBridge.service() ?: return false
        main.post {
            val existing = shown[id]?.takeIf { it.owner === service }
            if (existing != null) {
                existing.style = style
                apply(existing.view, style)
                runCatching { existing.owner.getSystemService(WindowManager::class.java).updateViewLayout(existing.view, existing.params) }
            } else {
                shown.remove(id)
                add(service, id, style)
            }
        }
        return true
    }

    /** Changes only the words of text [id], for a part that counts by itself; nothing happens when it is not shown. */
    fun setText(
        id: String,
        text: String,
    ) {
        main.post { shown[id]?.view?.takeIf { it.text.toString() != text }?.text = text }
    }

    fun hide(id: String) {
        main.post { shown.remove(id)?.let(::remove) }
    }

    fun hideAll() {
        main.post {
            shown.values.forEach(::remove)
            shown.clear()
        }
    }

    private fun add(
        service: PocketPilotA11yService,
        id: String,
        style: Style,
    ) {
        val manager = service.getSystemService(WindowManager::class.java)
        val metrics = service.resources.displayMetrics
        val view = TextView(service)
        apply(view, style)
        val maxWidth = metrics.widthPixels * style.maxWidthPercent.coerceIn(10, 100) / 100
        view.maxWidth = maxWidth
        view.measure(
            View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val params =
            WindowManager
                .LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    // 0% puts the text against the left or top edge, 100% against the right or bottom one.
                    x = (metrics.widthPixels - view.measuredWidth).coerceAtLeast(0) * style.xPercent.coerceIn(0, 100) / 100
                    y = (metrics.heightPixels - view.measuredHeight).coerceAtLeast(0) * style.yPercent.coerceIn(0, 100) / 100
                }
        val entry = Shown(view, params, service, style)
        view.setOnTouchListener(touch(entry, manager))
        runCatching { manager.addView(view, params) }.onSuccess { shown[id] = entry }
    }

    private fun apply(
        view: TextView,
        style: Style,
    ) {
        val dp = { value: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), view.resources.displayMetrics) }
        view.text = style.text
        view.setTextColor(style.textColor)
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, style.textSizeSp)
        view.gravity = style.align or Gravity.CENTER_VERTICAL
        val padding = dp(style.paddingDp).roundToInt()
        view.setPadding(padding, padding, padding, padding)
        view.background =
            GradientDrawable().apply {
                cornerRadius = dp(style.cornersDp)
                setColor(style.background)
            }
        view.alpha = style.opacity.coerceIn(0.05f, 1f)
    }

    private fun remove(entry: Shown) {
        runCatching { entry.owner.getSystemService(WindowManager::class.java).removeView(entry.view) }
    }

    /** A drag moves the text when it is movable; a tap or long press runs what the style says, without a drag. */
    private fun touch(
        entry: Shown,
        manager: WindowManager,
    ): View.OnTouchListener {
        val slop = ViewConfiguration.get(entry.view.context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var longPressed = false
        val longPress =
            Runnable {
                longPressed = true
                entry.style.onLongPress?.invoke()
            }
        return View.OnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = entry.params.x
                    startY = entry.params.y
                    moved = false
                    longPressed = false
                    if (entry.style.onLongPress != null) v.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!moved && hypot(dx, dy) > slop) {
                        moved = true
                        v.removeCallbacks(longPress)
                    }
                    if (moved && entry.style.movable) {
                        entry.params.x = (startX + dx).roundToInt()
                        entry.params.y = (startY + dy).roundToInt()
                        runCatching { manager.updateViewLayout(v, entry.params) }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPress)
                    if (!moved && !longPressed) {
                        entry.style.onTap?.invoke()
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(longPress)
                    true
                }

                else -> {
                    false
                }
            }
        }
    }
}
