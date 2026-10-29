package app.pocketpilot.core.automation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VariableWarningsTest {
    private fun rule(
        actions: List<ActionStep>,
        constraints: ConstraintGroup = ConstraintGroup(),
    ) = Rule(id = "r1", name = "Test", triggers = listOf(TriggerSpec("manual")), constraints = constraints, actions = actions)

    @Test
    fun `placeholder names are read in order, with their dotted keys`() {
        assertEquals(listOf("a", "screen.index:20"), Placeholders.names("{a} and {screen.index:20}"))
        assertEquals(emptyList(), Placeholders.names("no placeholders"))
    }

    @Test
    fun `a read before its set, and a name nothing sets, are both flagged`() {
        val warnings =
            RuleValidator.variableWarnings(
                rule(
                    listOf(
                        ActionStep("notify.post", mapOf("title" to "{later}")),
                        ActionStep("var.set", mapOf("name" to "later", "value" to "x")),
                        ActionStep("notify.post", mapOf("title" to "{nothing}")),
                    ),
                ),
                globals = emptySet(),
            )

        assertEquals(2, warnings.size)
        assertTrue(warnings.single { "{later}" in it }.contains("before a step sets it"))
        assertTrue(warnings.single { "{nothing}" in it }.contains("no step or global variable sets"))
    }

    @Test
    fun `screen reads, dictionary keys, globals, built-ins and trigger fields are not flagged`() {
        val warnings =
            RuleValidator.variableWarnings(
                rule(
                    listOf(
                        ActionStep("screen.read", mapOf("into" to "screen")),
                        ActionStep("notify.post", mapOf("title" to "{screen.index:20} {global} {now.time} {rule.name} {trigger.title}")),
                    ),
                ),
                globals = setOf("global"),
            )

        assertEquals(emptyList(), warnings)
    }

    @Test
    fun `a variable a condition reads is checked too`() {
        val warnings =
            RuleValidator.variableWarnings(
                rule(
                    actions = listOf(ActionStep("notify.post", mapOf("title" to "hi"))),
                    constraints =
                        ConstraintGroup(
                            items =
                                listOf(
                                    ConstraintNode.Check(
                                        ConstraintSpec(
                                            "variable.compare",
                                            mapOf("name" to "run_found", "op" to "equals", "value" to "yes"),
                                        ),
                                    ),
                                ),
                        ),
                ),
                globals = emptySet(),
            )

        assertEquals(1, warnings.size)
        assertTrue(warnings.single().startsWith("A condition reads {run_found}"))
    }

    @Test
    fun `a variable set in a branch counts as set for the steps after it`() {
        val warnings =
            RuleValidator.variableWarnings(
                rule(
                    listOf(
                        ActionStep(
                            "flow.if",
                            children = listOf(ActionStep("var.set", mapOf("name" to "address", "value" to "x"))),
                        ),
                        ActionStep("notify.post", mapOf("title" to "{address}")),
                    ),
                ),
                globals = emptySet(),
            )

        assertEquals(emptyList(), warnings)
    }
}
