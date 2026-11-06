package app.pocketpilot.network.api

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class StatusPollTest {
    @Test
    fun connectingIsReadEverySecond() {
        val poll = StatusPoll()
        repeat(3) { assertEquals(1_000L, poll.next(connected = false, changed = false)) }
    }

    @Test
    fun anUnchangedLinkIsReadLessAndLessOftenUpToAMinute() {
        val poll = StatusPoll()
        val waits = List(7) { poll.next(connected = true, changed = false) }
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L, 60_000L), waits)
    }

    @Test
    fun aChangeOrADropGoesBackToTheShortWait() {
        val poll = StatusPoll()
        repeat(5) { poll.next(connected = true, changed = false) }
        assertEquals(5_000L, poll.next(connected = true, changed = true))
        assertEquals(10_000L, poll.next(connected = true, changed = false))
        assertEquals(1_000L, poll.next(connected = false, changed = true))
        assertEquals(5_000L, poll.next(connected = true, changed = true))
    }

    @Test
    fun aPokeEndsTheWaitAndShortensTheNextOne() =
        runTest {
            val poll = StatusPoll()
            repeat(5) { poll.next(connected = true, changed = false) }
            val waiting = launch { poll.await(connected = true, changed = false) }
            testScheduler.advanceTimeBy(2_000)
            poll.poke()
            waiting.join()
            assertEquals(2_000L, currentTime)
            assertEquals(5_000L, poll.next(connected = true, changed = false))
        }

    @Test
    fun withoutAPokeTheWaitRunsItsFullTime() =
        runTest {
            val poll = StatusPoll()
            poll.await(connected = true, changed = false)
            assertEquals(5_000L, currentTime)
        }
}
