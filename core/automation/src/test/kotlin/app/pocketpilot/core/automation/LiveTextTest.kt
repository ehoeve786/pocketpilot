package app.pocketpilot.core.automation

import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveTextTest {
    private val start = 1_000_000L
    private val watches = mapOf("uber_order" to Stopwatch(startedAtMillis = start))

    private fun at(secondsIn: Long) = start + secondsIn * 1_000

    @Test
    fun aStopwatchCountsUpFromItsStart() {
        assertEquals("Order 4:07", LiveText.render("Order {stopwatch:uber_order}", at(247), watches, ZoneOffset.UTC))
        assertEquals("1:00:05", LiveText.render("{stopwatch:uber_order}", at(3_605), watches, ZoneOffset.UTC))
    }

    @Test
    fun aCountdownReachesZeroAndStaysThere() {
        val text = "Unassign in {countdown:uber_order:1200}"
        assertEquals("Unassign in 20:00", LiveText.render(text, at(0), watches, ZoneOffset.UTC))
        assertEquals("Unassign in 0:01", LiveText.render(text, at(1_199) + 500, watches, ZoneOffset.UTC))
        assertEquals("Unassign in 0:00", LiveText.render(text, at(1_200), watches, ZoneOffset.UTC))
        assertEquals("Unassign in 0:00", LiveText.render(text, at(5_000), watches, ZoneOffset.UTC))
    }

    @Test
    fun aPausedOrUnknownStopwatchHoldsStill() {
        val paused = mapOf("w" to Stopwatch(elapsedMillis = 65_000))
        assertEquals("1:05", LiveText.render("{stopwatch:w}", at(999), paused, ZoneOffset.UTC))
        assertEquals("0:00", LiveText.render("{stopwatch:nope}", at(999), paused, ZoneOffset.UTC))
    }

    @Test
    fun theClockShowsTheTimeInTheZone() {
        assertEquals("It is 00:16:40", LiveText.render("It is {clock}", 1_000_000, emptyMap(), ZoneOffset.UTC))
    }

    @Test
    fun onlyLiveTextIsDrawnEverySecond() {
        assertTrue(LiveText.isLive("Left: {countdown:a:60}"))
        assertTrue(LiveText.isLive("{clock}"))
        assertFalse(LiveText.isLive("Hello {name}"))
        assertFalse(LiveText.isLive("{countdown:a:{secs}}"))
    }

    @Test
    fun theValidatorDoesNotWarnAboutLiveParts() {
        val rule =
            Rule(
                id = "r",
                name = "Timer",
                triggers = listOf(TriggerSpec("manual")),
                actions =
                    listOf(
                        ActionStep("overlay.text", mapOf("text" to "{stopwatch:w} {countdown:w:60} {clock} {missing}")),
                    ),
            )
        val warnings = RuleValidator.variableWarnings(rule, emptySet())
        assertEquals(1, warnings.size)
        assertTrue("{missing}" in warnings.single())
    }
}
