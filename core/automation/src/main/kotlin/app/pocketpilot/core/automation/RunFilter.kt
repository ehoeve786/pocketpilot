package app.pocketpilot.core.automation

/** What the History tab shows: one rule's runs or every rule's, with the chosen outcomes or all of them. */
data class RunFilter(
    /** The rule to show; null for every rule. */
    val ruleId: String? = null,
    /** The outcomes to show; empty for all. */
    val outcomes: Set<RunOutcome> = emptySet(),
) {
    fun matches(run: RunRecord): Boolean = (ruleId == null || run.ruleId == ruleId) && (outcomes.isEmpty() || run.outcome in outcomes)

    fun apply(runs: List<RunRecord>): List<RunRecord> = runs.filter(::matches)

    /** [outcome] added to the shown outcomes, or taken out when it is shown already. */
    fun toggle(outcome: RunOutcome): RunFilter = copy(outcomes = if (outcome in outcomes) outcomes - outcome else outcomes + outcome)

    companion object {
        /** The rules that have runs, as id to the name of their latest run, in the order of their latest runs. */
        fun rules(runs: List<RunRecord>): List<Pair<String, String>> =
            runs.sortedByDescending { it.startedAtMillis }.distinctBy { it.ruleId }.map { it.ruleId to it.ruleName }

        /** How many runs of each outcome [ruleId] (or every rule, for null) has. */
        fun counts(
            runs: List<RunRecord>,
            ruleId: String?,
        ): Map<RunOutcome, Int> = runs.filter { ruleId == null || it.ruleId == ruleId }.groupingBy { it.outcome }.eachCount()
    }
}
