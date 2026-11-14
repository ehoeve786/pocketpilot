package app.pocketpilot.core.automation

import kotlin.test.Test
import kotlin.test.assertEquals

/** What the owner reads about each rule. Unknown types must say so rather than fail or print nothing. */
class DescribeTest {
    private fun check(type: String) = ConstraintNode.Check(ConstraintSpec(type))

    @Test
    fun `an unknown trigger, condition or action says it is unknown`() {
        assertEquals("Unknown trigger no.such", Describe.trigger(TriggerSpec("no.such")))
        assertEquals("Unknown condition no.such", Describe.constraint(ConstraintSpec("no.such")))
        assertEquals("Unknown action no.such", Describe.action(ActionStep("no.such")))
    }

    @Test
    fun `group modes join their conditions in plain words`() {
        val a = check("no.such.a")
        val b = check("no.such.b")

        fun group(mode: GroupMode) = Describe.group(ConstraintGroup(mode, listOf(a, b)))

        assertEquals("Unknown condition no.such.a and Unknown condition no.such.b", group(GroupMode.ALL))
        assertEquals("Unknown condition no.such.a or Unknown condition no.such.b", group(GroupMode.ANY))
        assertEquals("not Unknown condition no.such.a or Unknown condition no.such.b", group(GroupMode.NONE))
        assertEquals("exactly one of Unknown condition no.such.a, Unknown condition no.such.b", group(GroupMode.ONE))
    }

    @Test
    fun `app fields ask for the name of each package`() {
        val spec = TriggerSpec("app.opened", mapOf("apps" to "com.ubercab.driver,com.doordash.driverapp"))
        val asked = mutableListOf<String>()

        Describe.trigger(spec) { pkg ->
            asked += pkg
            pkg
        }

        assertEquals(listOf("com.ubercab.driver", "com.doordash.driverapp"), asked)
    }

    @Test
    fun `nested groups are wrapped in brackets`() {
        val inner = ConstraintGroup(GroupMode.ANY, listOf(check("no.such.a"), check("no.such.b")))
        val outer = ConstraintGroup(GroupMode.ALL, listOf(ConstraintNode.Group(inner), check("no.such.c")))

        assertEquals(
            "(Unknown condition no.such.a or Unknown condition no.such.b) and Unknown condition no.such.c",
            Describe.group(outer),
        )
    }

    @Test
    fun `rule actions name their rule when it is known`() {
        val names = mapOf("r1" to "Courier: refresh")
        val ruleName: (String) -> String = { names[it].orEmpty() }

        assertEquals(
            "Turn \"Courier: refresh\" on",
            Describe.action(ActionStep("flow.set_rule", mapOf("rule" to "r1", "state" to "on")), ruleName = ruleName),
        )
        assertEquals("Run \"Courier: refresh\"", Describe.action(ActionStep("flow.run_rule", mapOf("rule" to "r1")), ruleName = ruleName))
        assertEquals(
            "Turn a rule off",
            Describe.action(ActionStep("flow.set_rule", mapOf("rule" to "gone", "state" to "off")), ruleName = ruleName),
        )
        assertEquals("Run another rule", Describe.action(ActionStep("flow.run_rule", mapOf("rule" to "r1"))))
    }

    @Test
    fun `variable steps read as sentences`() {
        fun compare(
            op: String,
            value: String = "",
        ) = Describe.constraint(ConstraintSpec("variable.compare", mapOf("name" to "trigger.text", "op" to op, "value" to value)))

        assertEquals("{trigger.text} contains \"Extend run\"", compare("contains", "Extend run"))
        assertEquals("{trigger.text} is not \"x\"", compare("not_equals", "x"))
        assertEquals("{trigger.text} matches ^[0-9]+$", compare("matches", "^[0-9]+$"))
        assertEquals("{trigger.text} is not set", compare("not_set"))
        assertEquals("Clear {lt}", Describe.action(ActionStep("var.set", mapOf("name" to "lt"))))
        assertEquals("{lt} = {s}", Describe.action(ActionStep("var.set", mapOf("name" to "lt", "value" to "{s}"))))
        assertEquals(
            "Extract [0-9]+ from {y}",
            Describe.action(ActionStep("var.text", mapOf("name" to "y", "op" to "extract", "find" to "[0-9]+"))),
        )
        assertEquals(
            "Call screen.snapshot into {s}",
            Describe.action(
                ActionStep(
                    "tool.call",
                    mapOf(
                        "tool" to "screen.snapshot",
                        "into" to "s",
                    ),
                ),
            ),
        )
        assertEquals("Wait up to 6 s until", Describe.action(ActionStep("flow.wait_until", mapOf("timeout" to "6"))))
    }
}
