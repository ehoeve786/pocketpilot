package app.pocketpilot.core.automation

import kotlinx.serialization.Serializable

/**
 * A rule as plain text for sharing by message or email: its name, then what starts it, what must hold
 * and what it does, one step per line. Ids, run history and the owner's settings are left out.
 */
fun Rule.shareText(): String =
    buildString {
        appendLine(name)
        appendLine()
        appendLine("When:")
        triggers.forEach { appendLine("- ${Describe.trigger(it)}") }
        if (!constraints.isEmpty) {
            appendLine()
            appendLine("Only if:")
            appendLine("- ${Describe.group(constraints)}")
        }
        appendLine()
        appendLine("Then:")
        if (runAs == RunMode.AGENT) {
            appendLine("- Agent task: $agentPrompt")
        } else {
            actions.forEachIndexed { i, step -> appendLine("${i + 1}. ${Describe.action(step)}") }
        }
    }.trimEnd()

/** Who a shared rule is proposed by, as the recipient's Proposals list shows it. */
const val SHARED_RULE_PROPOSER = "a PocketPilot user"

/** The MIME type of a shared rule file, which PocketPilot registers to open. */
const val SHARED_RULE_MIME_TYPE = "application/vnd.pocketpilot.rule+json"

/** The parts of a rule that travel in a shared file; the owner's id, run history and settings stay home. */
@Serializable
private data class SharedRule(
    val name: String,
    val triggers: List<TriggerSpec> = emptyList(),
    val constraints: ConstraintGroup = ConstraintGroup(),
    val actions: List<ActionStep> = emptyList(),
    val runAs: RunMode = RunMode.DIRECT,
    val agentPrompt: String = "",
)

/** The rule as the JSON text of a shared file. */
fun Rule.shareFileJson(): String {
    val shared = SharedRule(name, triggers, constraints, withoutCredentials(actions), runAs, agentPrompt)
    return AutomationJson.json.encodeToString(SharedRule.serializer(), shared)
}

/**
 * The steps with any setting whose name suggests a credential (headers with an Authorization line, tokens, passwords,
 * cookies, API keys) removed, so a shared file never carries the owner's credentials. The receiver types those again.
 */
private fun withoutCredentials(steps: List<ActionStep>): List<ActionStep> =
    steps.map { step ->
        step.copy(
            params = step.params.filterKeys { key -> CREDENTIAL_WORDS.none { key.lowercase().contains(it) } },
            children = withoutCredentials(step.children),
            elseChildren = withoutCredentials(step.elseChildren),
        )
    }

/** The rule with every credential-named setting removed, for places that save a rule outside the store. */
fun Rule.withoutCredentialParams(): Rule = copy(actions = withoutCredentials(actions))

/**
 * The rule in a shared file's [json], as a disabled proposal from [SHARED_RULE_PROPOSER] with the id from
 * [newId]. Returns null when the file is not a rule.
 */
fun parseSharedRule(
    json: String,
    newId: () -> String,
): Rule? =
    runCatching {
        val shared = AutomationJson.json.decodeFromString(SharedRule.serializer(), json)
        val name = shared.name.trim()
        if (name.isEmpty()) return@runCatching null
        Rule(
            id = newId(),
            name = name,
            enabled = false,
            triggers = shared.triggers,
            constraints = shared.constraints,
            actions = shared.actions,
            runAs = shared.runAs,
            agentPrompt = shared.agentPrompt,
            proposedBy = SHARED_RULE_PROPOSER,
        )
    }.getOrNull()

/**
 * A shared rule as the owner sees it before accepting. [problems] stop the import; [warnings] are variables the
 * rule reads that this phone does not set, and show on the proposal without stopping it.
 */
data class SharedRuleCheck(
    val rule: Rule,
    val problems: List<String>,
    val warnings: List<String>,
)

/** Parses a shared file and checks it against this phone's catalog and [globals]; null when it is not a rule. */
fun checkSharedRule(
    json: String,
    newId: () -> String,
    sideload: Boolean,
    globals: Set<String>,
): SharedRuleCheck? {
    val rule = parseSharedRule(json, newId) ?: return null
    return SharedRuleCheck(
        rule = rule,
        problems = RuleValidator.problems(rule, sideload),
        warnings = RuleValidator.variableWarnings(rule, globals),
    )
}
