package app.pocketpilot.capability.api.shell

import kotlinx.coroutines.flow.Flow

/** A [SystemReader] that can also follow the system log as it is written. */
interface LogFollower : SystemReader {
    /**
     * Lines of the log [buffers] from [since] on, in `logcat -v epoch` format, as they are written. Only lines that
     * contain one of [needles], ignoring case, are sent: the filtering happens where the log is read, so this process
     * wakes only for lines it may want. The flow ends when the reader goes away; collect it again to go on.
     * [LogFollowArgs] says what is allowed.
     */
    fun followLog(
        buffers: List<String>,
        since: String,
        needles: Set<String>,
    ): Flow<String>
}

/** Checks and builds the `logcat` command behind [LogFollower.followLog]; the privileged service runs only this. */
object LogFollowArgs {
    /** The system buffer holds most of the phone's personal log text, so only these can be followed. */
    val BUFFERS = setOf("main", "crash")
    const val MAX_NEEDLES = 50
    const val MAX_NEEDLE = 200

    /** Unix seconds with milliseconds, as `logcat -v epoch` prints and `-T` reads. */
    private val SINCE = Regex("^\\d{1,12}\\.\\d{3}$")

    /** The command for following [buffers] from [since]; throws [IllegalArgumentException] for anything else. */
    fun argv(
        buffers: List<String>,
        since: String,
        needles: List<String>,
    ): List<String> {
        require(buffers.isNotEmpty() && buffers.all { it in BUFFERS }) { "Only the main and crash logs can be followed" }
        require(SINCE.matches(since)) { "since must be Unix seconds with three decimals" }
        // At least one needle, so the whole log is never streamed.
        require(needles.size in 1..MAX_NEEDLES && needles.all { it.isNotBlank() && it.length <= MAX_NEEDLE }) {
            "Give 1 to $MAX_NEEDLES texts of at most $MAX_NEEDLE characters"
        }
        return listOf("logcat", "-v", "epoch", "-T", since, "-b", buffers.distinct().joinToString(","))
    }

    fun matches(
        line: String,
        needles: List<String>,
    ): Boolean = needles.any { line.contains(it, ignoreCase = true) }
}
