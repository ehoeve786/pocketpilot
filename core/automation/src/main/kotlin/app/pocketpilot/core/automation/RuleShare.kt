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
            // The message goes to whichever app the owner picks, so credential values are left out of it too.
            withoutCredentials(actions).forEachIndexed { i, step -> appendLine("${i + 1}. ${Describe.action(step)}") }
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
    // Defaults keep files shared before these were added readable.
    val category: String = "",
    val variables: Map<String, String> = emptyMap(),
    val variableTypes: Map<String, VariableType> = emptyMap(),
    // Ids differ per phone, so the rules it names travel by name: the receiver links each to its own rule of that name.
    val ruleNames: Map<String, String> = emptyMap(),
)

/**
 * The rule as the JSON text of a shared file. Variables named like credentials stay home, as step settings do.
 * [nameOf] names the other rules it refers to, so the receiving phone can find its own copies.
 */
fun Rule.shareFileJson(nameOf: (String) -> String? = { null }): String {
    val kept = variables.filterKeys { !isSecretVariable(it) }
    val refs = mutableSetOf<String>()
    mapRuleRefs { it.also(refs::add) }
    val shared =
        SharedRule(
            name,
            triggers,
            constraints,
            withoutCredentials(actions),
            runAs,
            agentPrompt,
            category,
            kept,
            variableTypes.filterKeys { it in kept },
            refs.mapNotNull { id -> nameOf(id)?.let { id to it } }.toMap(),
        )
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

/** The rule with each credential value replaced by a mask, for showing on screen. */
fun Rule.withMaskedCredentials(): Rule = copy(actions = masked(actions))

private fun masked(steps: List<ActionStep>): List<ActionStep> =
    steps.map { step ->
        step.copy(
            params = step.params.mapValues { (key, value) -> if (isCredentialKey(key) && value.isNotBlank()) MASK else value },
            children = masked(step.children),
            elseChildren = masked(step.elseChildren),
        )
    }

private const val MASK = "••••••"

/** The rule with every credential-named setting removed, for places that save a rule outside the store. */
fun Rule.withoutCredentialParams(): Rule = copy(actions = withoutCredentials(actions))

/**
 * The rule in a shared file's [json], as a disabled proposal from [SHARED_RULE_PROPOSER] with the id from
 * [newId]. Returns null when the file is not a rule.
 */
fun parseSharedRule(
    json: String,
    newId: () -> String,
): Rule? = parseShared(json, newId) { null }?.first

/** The shared rule with each rule it names linked by [idOf] to this phone's rule, and the names [idOf] did not find. */
private fun parseShared(
    json: String,
    newId: () -> String,
    idOf: (String) -> String?,
): Pair<Rule, List<String>>? =
    runCatching {
        val shared = AutomationJson.json.decodeFromString(SharedRule.serializer(), json)
        val name = shared.name.trim()
        if (name.isEmpty()) return@runCatching null
        val missing = linkedSetOf<String>()
        val rule =
            Rule(
                id = newId(),
                name = name,
                enabled = false,
                triggers = shared.triggers,
                constraints = shared.constraints,
                actions = shared.actions,
                runAs = shared.runAs,
                agentPrompt = shared.agentPrompt,
                category = shared.category.trim(),
                variables = shared.variables,
                variableTypes = shared.variableTypes,
                proposedBy = SHARED_RULE_PROPOSER,
            ).mapRuleRefs { id ->
                val refName = shared.ruleNames[id] ?: return@mapRuleRefs id
                idOf(refName) ?: id.also { missing += refName }
            }
        rule to missing.toList()
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

/**
 * Parses a shared file and checks it against this phone's catalog and [globals]; null when it is not a rule.
 * [idOf] finds this phone's rule of a name, for the rules the shared one turns on, runs or checks.
 */
fun checkSharedRule(
    json: String,
    newId: () -> String,
    sideload: Boolean,
    globals: Set<String>,
    idOf: (String) -> String? = { null },
): SharedRuleCheck? {
    val (rule, missing) = parseShared(json, newId, idOf) ?: return null
    return SharedRuleCheck(
        rule = rule,
        problems = RuleValidator.problems(rule, sideload),
        warnings =
            RuleValidator.variableWarnings(rule, globals) +
                missing.map { "It uses the rule \"$it\", which this phone does not have" },
    )
}

/**
 * The rule with each reference to another rule (the "rule" setting of a trigger, condition or step, such as
 * "Turn a rule on or off") replaced by [transform].
 */
internal fun Rule.mapRuleRefs(transform: (String) -> String): Rule {
    fun params(map: Map<String, String>) =
        map.mapValues { (key, value) -> if (key == RULE_REF && value.isNotBlank()) transform(value) else value }

    fun group(g: ConstraintGroup): ConstraintGroup =
        g.copy(
            items =
                g.items.map {
                    when (it) {
                        is ConstraintNode.Check -> it.copy(spec = it.spec.copy(params = params(it.spec.params)))
                        is ConstraintNode.Group -> it.copy(group = group(it.group))
                    }
                },
        )

    fun steps(list: List<ActionStep>): List<ActionStep> =
        list.map {
            it.copy(
                params = params(it.params),
                constraints = group(it.constraints),
                children = steps(it.children),
                elseChildren = steps(it.elseChildren),
            )
        }
    return copy(
        triggers = triggers.map { it.copy(params = params(it.params)) },
        constraints = group(constraints),
        actions = steps(actions),
    )
}

private const val RULE_REF = "rule"
