package app.pocketpilot.core.automation

import kotlin.test.Test
import kotlin.test.assertEquals

class RunFilterTest {
    private fun run(
        id: String,
        rule: String,
        outcome: RunOutcome,
        at: Long,
        name: String = rule.uppercase(),
    ) = RunRecord(id, rule, name, "Run button", at, at, outcome)

    private val runs =
        listOf(
            run("1", "a", RunOutcome.OK, 50),
            run("2", "b", RunOutcome.SKIPPED, 40),
            run("3", "a", RunOutcome.FAILED, 30, name = "Old A"),
            run("4", "c", RunOutcome.OK, 20),
        )

    @Test
    fun showsEverythingByDefault() {
        assertEquals(runs, RunFilter().apply(runs))
    }

    @Test
    fun picksOneRule() {
        assertEquals(listOf("1", "3"), RunFilter(ruleId = "a").apply(runs).map { it.id })
    }

    @Test
    fun picksOutcomesAndCombinesWithTheRule() {
        val failedOrSkipped = RunFilter().toggle(RunOutcome.FAILED).toggle(RunOutcome.SKIPPED)
        assertEquals(listOf("2", "3"), failedOrSkipped.apply(runs).map { it.id })
        assertEquals(listOf("3"), failedOrSkipped.copy(ruleId = "a").apply(runs).map { it.id })
        assertEquals(setOf(RunOutcome.SKIPPED), failedOrSkipped.toggle(RunOutcome.FAILED).outcomes)
    }

    @Test
    fun listsRulesByLatestRunWithTheirLatestName() {
        assertEquals(listOf("a" to "A", "b" to "B", "c" to "C"), RunFilter.rules(runs))
    }

    @Test
    fun countsOutcomes() {
        assertEquals(mapOf(RunOutcome.OK to 2, RunOutcome.SKIPPED to 1, RunOutcome.FAILED to 1), RunFilter.counts(runs, null))
        assertEquals(mapOf(RunOutcome.OK to 1, RunOutcome.FAILED to 1), RunFilter.counts(runs, "a"))
    }
}
