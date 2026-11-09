package app.pocketpilot.core.automation

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The parts of a floating text that change by themselves, filled in each second while it shows:
 * `{stopwatch:name}` counts up, `{countdown:name:seconds}` counts down to zero from the stopwatch's start, and
 * `{clock}` is the time of day. Rule variables are filled in before, so `{countdown:uber_order:{secs}}` works.
 */
object LiveText {
    private val TOKEN = Regex("""\{(stopwatch|countdown):([A-Za-z_][A-Za-z0-9_.-]*)(?::(\d{1,7}))?\}|\{clock\}""")
    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss")

    /** True when [text] has a part that changes by itself, so it is drawn again every second. */
    fun isLive(text: String): Boolean = TOKEN.containsMatchIn(text)

    /** [text] with each live part filled in for [nowMillis]; a stopwatch that was never started counts from zero. */
    fun render(
        text: String,
        nowMillis: Long,
        stopwatches: Map<String, Stopwatch>,
        zone: ZoneId,
    ): String =
        TOKEN.replace(text) { match ->
            val (kind, name, seconds) = match.destructured
            when {
                match.value == "{clock}" -> {
                    CLOCK.format(Instant.ofEpochMilli(nowMillis).atZone(zone))
                }

                kind == "stopwatch" -> {
                    duration(stopwatches[name]?.millis(nowMillis) ?: 0)
                }

                seconds.isEmpty() -> {
                    match.value
                }

                else -> {
                    val elapsed = stopwatches[name]?.millis(nowMillis) ?: 0
                    // Rounded up, so a countdown shows 0:00 only once its time is really over.
                    duration((seconds.toLong() * MS_PER_SECOND - elapsed).coerceAtLeast(0) + MS_PER_SECOND - 1)
                }
            }
        }

    /** "4:07" under an hour, "1:04:07" from an hour on. */
    fun duration(millis: Long): String {
        val total = millis / MS_PER_SECOND
        val hours = total / SECONDS_PER_HOUR
        val minutes = total % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
        val seconds = total % SECONDS_PER_MINUTE
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
        }
    }

    private const val MS_PER_SECOND = 1_000L
    private const val SECONDS_PER_MINUTE = 60
    private const val SECONDS_PER_HOUR = 3_600
}
