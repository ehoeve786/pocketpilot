package app.pocketpilot.core.automation

import app.pocketpilot.core.model.RiskTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AcceptCheckTest {
    private val risks =
        mapOf(
            "sms.send" to RiskTier.SENSITIVE,
            "files.delete" to RiskTier.DESTRUCTIVE,
            "clock.now" to RiskTier.READ,
        )
    private val riskOf: (String) -> RiskTier? = { risks[it] }

    private fun ruleCalling(tool: String) =
        Rule(
            id = "r1",
            name = "Test",
            triggers = listOf(TriggerSpec("no.such.trigger")),
            actions = listOf(ActionStep("tool.call", params = mapOf("tool" to tool))),
        )

    @Test
    fun `a rule that may send a message needs the check and names the tool`() {
        val rule = ruleCalling("sms.send")
        assertTrue(rule.needsAcceptCheck(riskOf))
        assertEquals(listOf("sms.send"), rule.sensitiveTools(riskOf))
    }

    @Test
    fun `a destructive tool needs the check`() {
        assertTrue(ruleCalling("files.delete").needsAcceptCheck(riskOf))
    }

    @Test
    fun `a read-only rule does not need the check`() {
        val rule = ruleCalling("clock.now")
        assertFalse(rule.needsAcceptCheck(riskOf))
        assertEquals(emptyList(), rule.sensitiveTools(riskOf))
    }

    @Test
    fun `a tool the registry does not know is treated as sensitive`() {
        assertTrue(ruleCalling("made.up").needsAcceptCheck(riskOf))
    }

    @Test
    fun `a change to an existing rule needs the check even when read-only`() {
        assertTrue(ruleCalling("clock.now").copy(replaces = "r0").needsAcceptCheck(riskOf))
    }
}
