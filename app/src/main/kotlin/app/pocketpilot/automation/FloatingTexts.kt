package app.pocketpilot.automation

import android.content.Context
import android.graphics.Color
import android.os.PowerManager
import android.view.Gravity
import app.pocketpilot.capability.accessibility.A11yFloatingText
import app.pocketpilot.core.automation.AutomationFailure
import app.pocketpilot.core.automation.ColorText
import app.pocketpilot.core.automation.LiveText
import app.pocketpilot.core.automation.Stopwatch
import app.pocketpilot.core.common.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * The "Floating text" action: shows, changes and hides texts over other apps. A text with a live part, such as
 * `{stopwatch:name}`, is drawn again once a second while it shows and the screen is on; other texts never wake anything.
 */
class FloatingTexts(
    context: Context,
    private val scope: CoroutineScope,
    private val stopwatches: () -> Map<String, Stopwatch>,
    /** Starts the rule with this id, for a tap or long press. */
    private val startRule: (String) -> Unit,
    private val clock: Clock = Clock.System,
) {
    private val power = context.getSystemService(PowerManager::class.java)

    /** The live texts that show, by identifier, as written with their live parts still in. */
    private val live = ConcurrentHashMap<String, String>()
    private val autoHide = ConcurrentHashMap<String, Job>()
    private var ticker: Job? = null

    /** Runs one "Floating text" step with its settings filled in; returns what the run log shows. */
    fun handle(params: Map<String, String>): String {
        val id = params["id"]?.trim().orEmpty().ifEmpty { "1" }
        return when (params["mode"]) {
            "hide" -> {
                hide(id)
                "hid $id"
            }

            "hide_all" -> {
                live.keys.toList().forEach(::forget)
                A11yFloatingText.hideAll()
                "hid all"
            }

            else -> {
                show(id, params)
            }
        }
    }

    private fun show(
        id: String,
        params: Map<String, String>,
    ): String {
        val template = params["text"].orEmpty()
        if (template.isBlank()) throw AutomationFailure("Give the text to show")
        val style =
            A11yFloatingText.Style(
                text = render(template),
                textColor = color(params["color"], "Text colour", Color.WHITE),
                background = color(params["background"], "Background colour", Color.BLACK),
                textSizeSp = number(params["size"], 14.0, 6.0, 72.0).toFloat(),
                paddingDp = number(params["padding"], 8.0, 0.0, 64.0).toInt(),
                cornersDp = number(params["corners"], 12.0, 0.0, 64.0).toInt(),
                opacity = (number(params["opacity"], 100.0, 0.0, 100.0) / 100).toFloat(),
                align =
                    when (params["align"]) {
                        "left" -> Gravity.START
                        "right" -> Gravity.END
                        else -> Gravity.CENTER_HORIZONTAL
                    },
                xPercent = number(params["x"], 50.0, 0.0, 100.0).toInt(),
                yPercent = number(params["y"], 50.0, 0.0, 100.0).toInt(),
                maxWidthPercent = number(params["width"], 90.0, 10.0, 100.0).toInt(),
                movable = params["move"] != "false",
                onTap = onTap(id, runs(params["tap_rule"]), params["hide_on_tap"] == "true"),
                onLongPress = runs(params["long_rule"]),
            )
        if (!A11yFloatingText.show(id, style)) {
            throw AutomationFailure("Floating text needs PocketPilot's Accessibility service; turn it on in Settings > Accessibility")
        }
        if (LiveText.isLive(template)) {
            live[id] = template
            tick()
        } else {
            live.remove(id)
        }
        autoHide.remove(id)?.cancel()
        val seconds = number(params["auto_hide"], 0.0, 0.0, 86_400.0)
        if (seconds > 0) {
            autoHide[id] =
                scope.launch {
                    delay((seconds * MS_PER_SECOND).toLong())
                    hide(id)
                }
        }
        return "showing $id"
    }

    /** What a tap does: runs [rule], then hides the text when [thenHide], so its live part stops being drawn too. */
    private fun onTap(
        id: String,
        rule: (() -> Unit)?,
        thenHide: Boolean,
    ): (() -> Unit)? =
        if (!thenHide) {
            rule
        } else {
            {
                rule?.invoke()
                hide(id)
            }
        }

    /** Starts the rule [id] names, or null for none. */
    private fun runs(id: String?): (() -> Unit)? {
        val rule = id?.trim().orEmpty()
        return if (rule.isEmpty()) null else ({ startRule(rule) })
    }

    private fun hide(id: String) {
        forget(id)
        A11yFloatingText.hide(id)
    }

    private fun forget(id: String) {
        live.remove(id)
        autoHide.remove(id)?.cancel()
    }

    /** Draws the live texts again each second; ends by itself once none shows. */
    @Synchronized
    private fun tick() {
        if (ticker?.isActive == true) return
        ticker =
            scope.launch {
                while (isActive && live.isNotEmpty()) {
                    // Wakes on the next whole second, so a countdown changes in step with a clock.
                    delay(MS_PER_SECOND - clock.nowMillis() % MS_PER_SECOND)
                    // With the screen off nobody can see it; the next tick after it turns on catches up.
                    if (!power.isInteractive) continue
                    // The Accessibility service went away and took the texts with it: nothing is left to redraw.
                    if (!A11yFloatingText.available()) {
                        live.clear()
                        break
                    }
                    live.forEach { (id, template) -> A11yFloatingText.setText(id, render(template)) }
                }
            }
    }

    private fun render(template: String): String = LiveText.render(template, clock.nowMillis(), stopwatches(), ZoneId.systemDefault())

    private fun color(
        value: String?,
        name: String,
        default: Int,
    ): Int {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return default
        return ColorText.argb(text) ?: throw AutomationFailure("$name must be a colour such as #000000 or #80FFFFFF, not $text")
    }

    private fun number(
        value: String?,
        default: Double,
        min: Double,
        max: Double,
    ): Double = (value?.trim()?.toDoubleOrNull() ?: default).coerceIn(min, max)

    private companion object {
        const val MS_PER_SECOND = 1_000L
    }
}
