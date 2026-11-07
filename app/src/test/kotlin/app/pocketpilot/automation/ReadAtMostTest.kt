package app.pocketpilot.automation

import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReadAtMostTest {
    @Test
    fun readsAReplyUpToTheLimit() {
        val text = "x".repeat(20_000)
        assertEquals(text, StringReader(text).readAtMost(20_000))
        assertEquals("", StringReader("").readAtMost(10))
    }

    @Test
    fun refusesAReplyOverTheLimitInsteadOfCuttingIt() {
        assertNull(StringReader("x".repeat(20_001)).readAtMost(20_000))
    }

    @Test
    fun aHalfMegabyteEntityListFitsTheListLimit() {
        val entity = { i: Int -> """{"entity_id":"sensor.s$i","state":"on","attributes":{"friendly_name":"S $i"}}""" }
        val states = List(5_000, entity).joinToString(",", "[", "]")
        assertEquals(states, StringReader(states).readAtMost(8_000_000))
        assertNull(StringReader(states).readAtMost(100_000))
    }
}
