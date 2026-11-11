package app.pocketpilot.core.automation

import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemainingAuditTest {
    @Test
    fun `a battery level with none before it is not a crossing`() {
        val trigger = AutomationCatalog.trigger("battery.level")!!
        val params = mapOf("direction" to "below", "percent" to "20")
        assertFalse(trigger.match(params, mapOf("level" to "14"), null))
        assertTrue(trigger.match(params, mapOf("level" to "19"), mapOf("level" to "20")))
        assertFalse(trigger.match(params, mapOf("level" to "18"), mapOf("level" to "19")))
    }

    @Test
    fun `a window with the same start and end is the whole day`() {
        val noon = LocalTime.of(12, 0)
        assertTrue(Constraints.inWindow(LocalTime.of(3, 0), noon, noon))
        assertTrue(Constraints.inWindow(noon, noon, noon))
    }

    @Test
    fun `full day names read like their three-letter codes`() {
        assertEquals(setOf("Monday", "Friday"), RunRows.dayNames("monday, Fri"))
    }

    @Test
    fun `a cleared range bound stays open, a missing one gets its default`() {
        val fields = AutomationCatalog.constraint("battery.temperature")!!.fields
        assertEquals("45", RuleValidator.withDefaults(fields, mapOf("min" to "40"))["max"])
        assertEquals("", RuleValidator.withDefaults(fields, mapOf("min" to "40", "max" to ""))["max"])
        assertTrue(MoreConstraints.within(60.0, "40", ""))
    }
}
