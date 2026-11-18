package app.pocketpilot.automation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A floating window that shows an SSH command's output while it runs, for the "Live output" setting of
 * "Run an SSH command". Output comes from Go's threads; the window redraws at most every [REFRESH_MS] with the
 * last [MAX_LINES] lines. A window that cannot show, such as without Accessibility, never fails the command.
 */
internal class SshLiveWindow(
    private val floatingTexts: FloatingTexts,
    private val scope: CoroutineScope,
    private val title: String,
) {
    private val lock = Any()
    private val text = StringBuilder()

    @Volatile private var dirty = true
    private var ticker: Job? = null

    fun start() {
        ticker =
            scope.launch {
                while (isActive) {
                    if (dirty) draw(autoHideSeconds = 0)
                    delay(REFRESH_MS)
                }
            }
    }

    /** Takes a piece of output; stdout and stderr go into the window together, as a terminal shows them. */
    fun output(chunk: String) {
        synchronized(lock) {
            text.append(chunk)
            if (text.length > MAX_CHARS) text.delete(0, text.length - MAX_CHARS)
        }
        dirty = true
    }

    /** Adds [summary] and draws the window a last time; it hides after [autoHideSeconds], or stays until tapped at 0. */
    suspend fun finish(
        summary: String,
        autoHideSeconds: Int,
    ) {
        ticker?.cancelAndJoin()
        synchronized(lock) { if (text.isNotEmpty() && text.last() != '\n') text.append('\n') }
        output(summary)
        draw(autoHideSeconds)
    }

    private suspend fun draw(autoHideSeconds: Int) {
        dirty = false
        val body = synchronized(lock) { screenLines(text.toString()) }
        val shown = "$title\n" + body.ifEmpty { "…" }
        withContext(Dispatchers.Main) {
            runCatching {
                floatingTexts.handle(
                    mapOf(
                        "mode" to "show",
                        "id" to WINDOW_ID,
                        "text" to shown,
                        "align" to "left",
                        "size" to "12",
                        "y" to "35",
                        "width" to "95",
                        "opacity" to "92",
                        "hide_on_tap" to "true",
                        "auto_hide" to autoHideSeconds.toString(),
                        FloatingTexts.PLAIN to "true",
                    ),
                )
            }
        }
    }

    companion object {
        const val WINDOW_ID = "ssh_live"
        private const val REFRESH_MS = 250L
        private const val MAX_LINES = 20
        private const val MAX_CHARS = 16_000

        /**
         * The last [MAX_LINES] lines as a terminal would leave them: a carriage return starts its line again, as
         * git's progress counters do, so only what was written after the last one stays.
         */
        internal fun screenLines(output: String): String =
            output
                .trimEnd('\n')
                .split('\n')
                .map { line -> line.trimEnd('\r').substringAfterLast('\r') }
                .takeLast(MAX_LINES)
                .joinToString("\n")
    }
}
