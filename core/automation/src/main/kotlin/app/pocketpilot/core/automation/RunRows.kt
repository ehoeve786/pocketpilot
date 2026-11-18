package app.pocketpilot.core.automation

/** The element to tap, the snapshot it belongs to, and the day and time range it covers, such as "6:00 AM to 10:00 AM". */
data class RunRow(
    val snapshotId: String,
    val element: String,
    val day: String,
    val time: String,
)

/**
 * Finds the first run on a schedule list that starts inside a time window on the chosen days. Each run
 * row reads like `button desc="6:00 AM - 10:00 AM, GVA - Downtown"` and sits under its day's heading, such as
 * `text "Monday "`, in the order the screen lists them.
 */
object RunRows {
    private val SNAPSHOT = Regex("""^snapshot (\S+)""")
    private val DAY = Regex("""text "(Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday) *"""")
    private val ROW = Regex("""^\s*(e\d+) button desc="((\d{1,2}):(\d{2}) ([AP]M) - \d{1,2}:\d{2} [AP]M)[^"]*".*\bclick\b""")
    private const val DAY_CODE_LENGTH = 3
    private val DAY_CODES =
        mapOf(
            "mon" to "Monday",
            "tue" to "Tuesday",
            "wed" to "Wednesday",
            "thu" to "Thursday",
            "fri" to "Friday",
            "sat" to "Saturday",
            "sun" to "Sunday",
        )

    /** Full day names for codes such as "mon,fri" (case and spaces ignored); unknown codes are skipped. */
    fun dayNames(codes: String): Set<String> =
        codes
            .split(",")
            // "monday" and "Mon" read as "mon", as the rule checker accepts them.
            .mapNotNull { DAY_CODES[it.trim().lowercase().take(DAY_CODE_LENGTH)] }
            .toSet()

    /**
     * Start windows for single days, such as "mon 10:00-16:00, sat off", by full day name; an "off" day maps to null.
     * Entries that do not read as a day and a window are skipped.
     */
    fun dayHours(text: String): Map<String, IntRange?> =
        text
            .split(",")
            .mapNotNull<String, Pair<String, IntRange?>> { entry ->
                val (code, window) = entry.trim().split(' ', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
                val day = DAY_CODES[code.lowercase().take(DAY_CODE_LENGTH)] ?: return@mapNotNull null
                if (window.trim().equals("off", ignoreCase = true)) return@mapNotNull day to null
                val (start, end) = window.split('-').map { minutesOf(it.trim()) }.takeIf { it.size == 2 } ?: return@mapNotNull null
                if (start == null || end == null) null else day to start..end
            }.toMap()

    /**
     * The first run starting inside its day's window, or null: [hours] (from [dayHours]) for the days it names,
     * otherwise [from] to [to] (both HH:mm, inclusive) on [days] (full day names).
     */
    fun pick(
        snapshot: String,
        from: String,
        to: String,
        days: Set<String>,
        hours: Map<String, IntRange?> = emptyMap(),
    ): RunRow? {
        val earliest = minutesOf(from) ?: return null
        val latest = minutesOf(to) ?: return null
        val lines = snapshot.lines()
        val snapshotId = SNAPSHOT.find(lines.firstOrNull().orEmpty())?.groupValues?.get(1) ?: return null
        var day = ""
        for (line in lines.drop(1)) {
            val heading = DAY.find(line)
            val row = ROW.find(line)
            if (heading != null) {
                day = heading.groupValues[1]
            } else if (row != null) {
                val window = if (day in hours) hours[day] else (earliest..latest).takeIf { day in days }
                val (element, range, hour, minute, half) = row.destructured
                if (window != null && startMinutes(hour.toInt(), minute.toInt(), half) in window) {
                    return RunRow(snapshotId, element, day, spoken(range))
                }
            }
        }
        return null
    }

    /** "6:00 AM - 10:00 AM" as read aloud: "6:00am to 10:00am". */
    private fun spoken(range: String): String =
        range
            .replace(" AM", "am")
            .replace(" PM", "pm")
            .replace(" - ", " to ")

    /** Minutes after midnight for "HH:mm", or null when it is not a time. */
    private fun minutesOf(time: String): Int? {
        val parts = time.split(":")
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    /** Minutes after midnight for a 12-hour clock time, such as 12:30 AM (0:30) or 4:15 PM (16:15). */
    private fun startMinutes(
        hour: Int,
        minute: Int,
        half: String,
    ): Int = ((hour % 12) + if (half == "PM") 12 else 0) * 60 + minute
}
