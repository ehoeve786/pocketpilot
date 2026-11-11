package app.pocketpilot.core.automation

import app.pocketpilot.core.model.RiskTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecondAuditTest {
    private fun rule(vararg steps: ActionStep) =
        Rule(id = "r1", name = "Test", triggers = listOf(TriggerSpec("manual")), actions = steps.toList())

    @Test
    fun `a rule with a web request or an AI step needs the accept check, a plain one does not`() {
        val riskOf: (String) -> RiskTier? = { RiskTier.READ }
        assertTrue(rule(ActionStep("http.request", mapOf("url" to "https://example.com"))).needsAcceptCheck(riskOf))
        assertTrue(rule(ActionStep("ai.ask", mapOf("prompt" to "hi"))).needsAcceptCheck(riskOf))
        val nested = ActionStep("flow.if", children = listOf(ActionStep("screen.screenshot")))
        assertTrue(rule(nested).needsAcceptCheck(riskOf))
        assertFalse(rule(ActionStep("var.set", mapOf("name" to "a", "value" to "1"))).needsAcceptCheck(riskOf))
    }

    @Test
    fun `the catalog rates posting a notification and audio control as the tools do`() {
        assertEquals(RiskTier.SENSITIVE, AutomationCatalog.action("notify.post")?.tier)
        assertEquals(RiskTier.SENSITIVE, AutomationCatalog.action("audio.control")?.tier)
    }

    @Test
    fun `app actions take one app and a sensor reading is stored as its number`() {
        listOf("app.launch", "app.stop", "app.set_enabled", "app.clear_data").forEach { type ->
            val fields = AutomationCatalog.action(type)!!.fields
            assertTrue(fields.first { it.key == "apps" }.single, type)
        }
        val kind = AutomationCatalog.action("sensor.read")!!.kind as ActionKind.Tool
        assertEquals("123.4", kind.result("light: 123.4"))
    }

    @Test
    fun `a number field that is not a number is a problem, and a fractional interval still schedules`() {
        val bad = rule().copy(triggers = listOf(TriggerSpec("time.interval", mapOf("minutes" to "soon"))))
        assertTrue(RuleValidator.problems(bad).any { "must be a number" in it })
        val fractional = TriggerSpec("time.interval", mapOf("minutes" to "1.5"))
        assertEquals(120_000L, Schedule.next(fractional, after = 0, lastFiredAt = 0))
        val huge = TriggerSpec("time.interval", mapOf("minutes" to "9999999999999999"))
        assertTrue(Schedule.next(huge, after = 0, lastFiredAt = 0)!! > 0)
    }
}
