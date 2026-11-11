package app.pocketpilot.core.automation

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs a pattern from a rule on text from the phone with a budget. Java regular expressions can backtrack
 * for a very long time on some patterns (a shared rule can carry one), and a match cannot be interrupted, so
 * the text counts its own reads and the match stops once the budget is spent.
 *
 * Android's matcher copies the text and never calls back into it, so there the read budget does nothing. Each match
 * therefore also runs on a thread of its own with a time limit. A match that passes the limit cannot be stopped, only
 * left behind; while [MAX_STUCK] of them are still running, further patterns are refused rather than given a thread.
 */
object SafeRegex {
    /** Reads of the text allowed for one match; ordinary patterns on screen text use a few thousand. */
    private const val READ_BUDGET = 2_000_000

    /** How long one match may take; ordinary patterns on screen text take well under a millisecond. */
    private const val TIME_BUDGET_MS = 1_500L
    private const val MAX_STUCK = 2

    private val stuck = AtomicInteger(0)
    private val threads =
        Executors.newCachedThreadPool { work ->
            Thread(work, "automation-regex").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }

    private fun <T> limited(match: () -> T): T {
        if (stuck.get() >= MAX_STUCK) throw TooSlow("Earlier patterns are still running; this one is not started")
        // 0 while the match runs, 1 once the wait below gave up on it, 2 once it finished in time.
        val state = AtomicInteger(0)
        val running =
            threads.submit(
                Callable {
                    try {
                        match()
                    } finally {
                        if (!state.compareAndSet(0, 2)) stuck.decrementAndGet()
                    }
                },
            )
        try {
            return running.get(TIME_BUDGET_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            if (state.compareAndSet(0, 1)) stuck.incrementAndGet()
            throw TooSlow()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    class TooSlow(
        message: String = "That pattern takes too long on this text",
    ) : RuntimeException(message)

    private class Budgeted(
        private val text: String,
    ) : CharSequence {
        private var reads = 0

        override val length: Int get() = text.length

        override fun get(index: Int): Char {
            if (++reads > READ_BUDGET) throw TooSlow()
            return text[index]
        }

        override fun subSequence(
            startIndex: Int,
            endIndex: Int,
        ): CharSequence = text.substring(startIndex, endIndex)

        override fun toString(): String = text
    }

    /** The first match of [pattern] in [text], or null; throws [TooSlow] when the budget runs out. */
    fun find(
        pattern: String,
        text: String,
    ): MatchResult? = limited { Regex(pattern).find(Budgeted(text)) }

    /** True when [pattern] matches somewhere in [text]; throws [TooSlow] when the budget runs out. */
    fun matches(
        pattern: String,
        text: String,
    ): Boolean = limited { Regex(pattern).containsMatchIn(Budgeted(text)) }
}
