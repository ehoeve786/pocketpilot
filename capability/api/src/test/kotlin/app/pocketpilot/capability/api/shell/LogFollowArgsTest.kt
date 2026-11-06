package app.pocketpilot.capability.api.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogFollowArgsTest {
    @Test
    fun buildsAFollowingLogcatFromTheGivenTime() {
        assertEquals(
            listOf("logcat", "-v", "epoch", "-T", "1760212345.123", "-b", "main,crash"),
            LogFollowArgs.argv(listOf("main", "crash", "main"), "1760212345.123", listOf("boom")),
        )
    }

    @Test
    fun refusesOtherBuffers() {
        listOf("system", "events", "radio", "all", "main,system").forEach { buffer ->
            assertFailsWith<IllegalArgumentException> { LogFollowArgs.argv(listOf(buffer), "1.000", listOf("x")) }
        }
        assertFailsWith<IllegalArgumentException> { LogFollowArgs.argv(emptyList(), "1.000", listOf("x")) }
    }

    @Test
    fun refusesATimeThatIsNotEpochSeconds() {
        listOf("", "1", "1.0", "now", "1.000 -d", "-1.000").forEach { since ->
            assertFailsWith<IllegalArgumentException> { LogFollowArgs.argv(listOf("main"), since, listOf("x")) }
        }
    }

    @Test
    fun needsBetweenOneAndFiftyShortTexts() {
        assertFailsWith<IllegalArgumentException> { LogFollowArgs.argv(listOf("main"), "1.000", emptyList()) }
        assertFailsWith<IllegalArgumentException> { LogFollowArgs.argv(listOf("main"), "1.000", listOf(" ")) }
        assertFailsWith<IllegalArgumentException> { LogFollowArgs.argv(listOf("main"), "1.000", listOf("x".repeat(201))) }
        assertFailsWith<IllegalArgumentException> { LogFollowArgs.argv(listOf("main"), "1.000", List(51) { "t$it" }) }
        LogFollowArgs.argv(listOf("main"), "1.000", List(50) { "t$it" })
    }

    @Test
    fun matchesAnyTextIgnoringCase() {
        val line = "1760212345.123  1234  5678 E AndroidRuntime: Process: com.example, PID: 1234"
        assertTrue(LogFollowArgs.matches(line, listOf("nothing", "process: ")))
        assertTrue(LogFollowArgs.matches(line, listOf("androidruntime")))
        assertFalse(LogFollowArgs.matches(line, listOf("nothing")))
    }
}
