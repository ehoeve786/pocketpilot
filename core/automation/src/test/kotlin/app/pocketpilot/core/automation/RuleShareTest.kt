package app.pocketpilot.core.automation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuleShareTest {
    private val rule =
        Rule(
            id = "r1",
            name = "Morning",
            triggers = listOf(TriggerSpec("no.such.trigger")),
            constraints = ConstraintGroup.of(ConstraintSpec("no.such.condition")),
            actions = listOf(ActionStep("no.such.action")),
        )

    @Test
    fun `a shared rule lists its name, when, only if and then in plain words`() {
        assertEquals(
            """
            Morning

            When:
            - Unknown trigger no.such.trigger

            Only if:
            - Unknown condition no.such.condition

            Then:
            1. Unknown action no.such.action
            """.trimIndent(),
            rule.shareText(),
        )
    }

    @Test
    fun `the message text carries no credential value`() {
        val withHeader =
            rule.copy(
                actions =
                    listOf(
                        ActionStep(
                            "http.request",
                            mapOf(
                                "url" to "https://example.com",
                                "headers" to "Authorization: Bearer abc123",
                            ),
                        ),
                    ),
            )
        assertFalse(withHeader.shareText().contains("abc123"))
        assertFalse(withHeader.shareFileJson().contains("abc123"))
    }

    @Test
    fun `run history and the rule id are not shared`() {
        val withHistory = rule.copy(lastRun = RunSummary(atMillis = 1, outcome = RunOutcome.OK, actions = 1))

        val json = withHistory.shareFileJson()
        val received = parseSharedRule(json) { "new-id" }!!

        assertFalse(json.contains("lastRun"))
        assertFalse(json.contains("\"OK\""))
        assertFalse(withHistory.shareText().contains("OK"))
        assertEquals("new-id", received.id)
        assertNull(received.lastRun)
    }

    @Test
    fun `an agent rule shares its prompt as its only step`() {
        val agent = rule.copy(runAs = RunMode.AGENT, agentPrompt = "Check the mail", actions = emptyList())

        assertTrue(agent.shareText().endsWith("Then:\n- Agent task: Check the mail"))
        assertEquals("Check the mail", parseSharedRule(agent.shareFileJson()) { "x" }!!.agentPrompt)
    }

    @Test
    fun `the rules a floating text runs are rule references too`() {
        val overlay = rule.copy(actions = listOf(ActionStep("overlay.text", mapOf("text" to "Hi", "tap_rule" to "a", "long_rule" to "b"))))

        val mapped =
            overlay
                .mapRuleRefs { it.uppercase() }
                .actions
                .single()
                .params

        assertEquals(mapOf("text" to "Hi", "tap_rule" to "A", "long_rule" to "B"), mapped)
    }

    @Test
    fun `a shared rule with a blank name is not a rule`() {
        assertNull(parseSharedRule(rule.copy(name = "  ").shareFileJson()) { "x" })
    }

    @Test
    fun `a shared file opens as a disabled proposal with the same steps`() {
        val received = parseSharedRule(rule.shareFileJson()) { "new-id" }!!

        assertEquals("new-id", received.id)
        assertEquals("Morning", received.name)
        assertEquals(rule.triggers, received.triggers)
        assertEquals(rule.constraints, received.constraints)
        assertEquals(rule.actions, received.actions)
        assertEquals(SHARED_RULE_PROPOSER, received.proposedBy)
        assertFalse(received.enabled)
        assertTrue(received.pending)
    }

    @Test
    fun `a shared file keeps the category and variables but not credential variables`() {
        val withExtras =
            rule.copy(
                category = "Uber",
                variables = mapOf("first" to "yes", "count" to "2", "api_token" to "s3cret"),
                variableTypes = mapOf("count" to VariableType.INTEGER, "api_token" to VariableType.STRING),
            )
        val file = withExtras.shareFileJson()
        val received = parseSharedRule(file) { "new-id" }!!

        assertEquals("Uber", received.category)
        assertEquals(mapOf("first" to "yes", "count" to "2"), received.variables)
        assertEquals(mapOf("count" to VariableType.INTEGER), received.variableTypes)
        assertFalse("s3cret" in file)
    }

    @Test
    fun `a file without a category or variables still opens`() {
        val old = """{"name":"Old","actions":[{"type":"toast","params":{"text":"hi"}}]}"""
        val received = parseSharedRule(old) { "id" }!!

        assertEquals("", received.category)
        assertEquals(emptyMap(), received.variables)
    }

    @Test
    fun `text that is not a shared rule is not a rule`() {
        assertNull(parseSharedRule("hello there") { "x" })
        assertNull(parseSharedRule("{\"unrelated\":true}") { "x" })
    }

    @Test
    fun `rules a shared rule uses are linked by name to the receiver's own rules`() {
        val toggle =
            rule.copy(
                triggers = listOf(TriggerSpec("rule.finished", mapOf("rule" to "a1"))),
                constraints = ConstraintGroup.of(ConstraintSpec("rule.enabled", mapOf("rule" to "a1"))),
                actions =
                    listOf(
                        ActionStep(
                            "flow.if",
                            constraints = ConstraintGroup.of(ConstraintSpec("rule.running", mapOf("rule" to "a2"))),
                            children = listOf(ActionStep("flow.set_rule", mapOf("rule" to "a2", "state" to "off"))),
                        ),
                    ),
            )
        val file = toggle.shareFileJson(mapOf("a1" to "Auto accept", "a2" to "Unassign")::get)
        val check = checkSharedRule(file, { "new" }, sideload = true, globals = emptySet(), idOf = mapOf("Auto accept" to "b1")::get)!!
        val received = check.rule
        assertEquals("b1", received.triggers.single().params["rule"])
        assertEquals("b1", (received.constraints.items.single() as ConstraintNode.Check).spec.params["rule"])
        val step = received.actions.single()
        assertEquals("a2", (step.constraints.items.single() as ConstraintNode.Check).spec.params["rule"])
        assertEquals("a2", step.children.single().params["rule"])
        assertEquals(listOf("It uses the rule \"Unassign\", which this phone does not have"), check.warnings)
    }
}
