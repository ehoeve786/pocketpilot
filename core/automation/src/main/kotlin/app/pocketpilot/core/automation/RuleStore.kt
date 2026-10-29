package app.pocketpilot.core.automation

import app.pocketpilot.core.common.Clock
import app.pocketpilot.core.common.UlidGenerator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Where the store keeps its JSON. */
interface Persistence {
    fun load(): String?

    fun save(text: String)
}

/** Writes through a temporary file, so a crash mid-write never leaves half a file. */
class FilePersistence(
    private val file: File,
) : Persistence {
    override fun load(): String? = file.takeIf { it.exists() }?.readText()

    override fun save(text: String) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            file.writeText(text)
            temp.delete()
        }
    }
}

/** Keeps rules, global variables, settings and the run history, and saves every change at once. */
class RuleStore(
    private val persistence: Persistence,
    private val clock: Clock = Clock.System,
    private val ids: UlidGenerator = UlidGenerator(clock),
    /** Keeps credential values out of the rules file. Null keeps them in the file, as a plain store does. */
    private val secrets: RuleSecrets? = null,
) {
    /** As saved: credential values are references when [secrets] is set. */
    private val state = MutableStateFlow(load())

    /** The rules as the engine and the screens see them, with the credential values filled in again. */
    private val view = MutableStateFlow(open(state.value))

    val data: StateFlow<AutomationData> = view.asStateFlow()

    init {
        // A file written before the secret store existed still holds its credentials. Moving them now keeps them out of
        // the file from the first run on.
        if (secrets != null) update { it }
    }

    val rules: List<Rule> get() = view.value.rules

    fun rule(id: String): Rule? = view.value.rules.firstOrNull { it.id == id }

    fun newId(): String = ids.next()

    @Synchronized
    fun update(transform: (AutomationData) -> AutomationData) {
        val before = state.value
        val next = protect(transform(view.value), before)
        if (next == before) return
        state.value = next
        view.value = open(next)
        persistence.save(AutomationJson.json.encodeToString(AutomationData.serializer(), next))
    }

    /** Adds [rule], or replaces the rule with its id. */
    fun upsert(rule: Rule) =
        update { data ->
            val stamped = if (rule.createdAtMillis == 0L) rule.copy(createdAtMillis = clock.nowMillis()) else rule
            val index = data.rules.indexOfFirst { it.id == rule.id }
            data.copy(rules = if (index < 0) data.rules + stamped else data.rules.toMutableList().also { it[index] = stamped })
        }

    fun delete(id: String) = update { data -> data.copy(rules = data.rules.filterNot { it.id == id }) }

    fun setEnabled(
        id: String,
        enabled: Boolean,
    ) = edit(id) { if (it.pending) it else it.copy(enabled = enabled) }

    fun setPinned(
        id: String,
        pinned: Boolean,
    ) = edit(id) { it.copy(pinned = pinned) }

    /** The owner accepted a proposal: it becomes an ordinary rule and loses its "Proposed by" label. */
    fun accept(id: String) {
        rule(id)?.let { saveAccepted(it) }
    }

    /**
     * Saves an accepted proposal, perhaps edited by the owner first. One that changes an existing rule
     * updates that rule in place and goes away; any other becomes an ordinary rule.
     */
    fun saveAccepted(proposal: Rule) {
        val accepted = proposal.copy(proposedBy = null, enabled = true, replaces = null)
        val target = proposal.replaces?.let(::rule)
        if (target == null) return upsert(accepted)
        update { data ->
            // The owner's on or off switch for the rule being replaced stays as it was.
            val updated = target.changedTo(accepted)
            data.copy(rules = data.rules.filterNot { it.id == proposal.id }.map { if (it.id == target.id) updated else it })
        }
    }

    /**
     * Adds a rule shared from another phone as a proposal. When the owner already has a rule of the same
     * name, accepting it updates that rule in place, and a newer copy replaces an older one still waiting.
     */
    fun addShared(shared: Rule) =
        update { data ->
            fun sameName(rule: Rule) = rule.name.trim().equals(shared.name.trim(), ignoreCase = true)
            val waiting = data.rules.firstOrNull { it.pending && it.proposedBy == shared.proposedBy && sameName(it) }
            val target = data.rules.firstOrNull { !it.pending && sameName(it) }
            val proposal =
                shared.copy(
                    id = waiting?.id ?: shared.id,
                    replaces = target?.id,
                    createdAtMillis = waiting?.createdAtMillis ?: clock.nowMillis(),
                )
            data.copy(rules = if (waiting == null) data.rules + proposal else data.rules.map { if (it.id == waiting.id) proposal else it })
        }

    /** Changes rule [id] in place to [changed], keeping its id, switch, pin, creation time and last run. */
    fun change(
        id: String,
        changed: Rule,
    ) = edit(id) { it.changedTo(changed) }

    /** Sets a global variable, or removes it when [value] is null. [type] is kept when null. */
    fun setVariable(
        name: String,
        value: String?,
        type: VariableType? = null,
    ) = update { data ->
        data.copy(
            variables = if (value == null) data.variables - name else data.variables + (name to value),
            variableTypes =
                when {
                    value == null -> data.variableTypes - name
                    type != null -> data.variableTypes + (name to type)
                    else -> data.variableTypes
                },
        )
    }

    fun setSettings(settings: AutomationSettings) = update { it.copy(settings = settings) }

    /** Saves a finished run, keeping the newest [MAX_RUNS] and at most [MAX_HISTORY_CHARS] of their log lines. */
    fun addRun(record: RunRecord) =
        update { data ->
            data.copy(
                runs = keptRuns(listOf(record) + data.runs),
                rules =
                    data.rules.map {
                        if (it.id != record.ruleId || record.outcome == RunOutcome.SKIPPED) {
                            it
                        } else {
                            it.copy(
                                lastRun = RunSummary(record.endedAtMillis, record.outcome, record.lines.size, record.lines.lastOrNull()),
                            )
                        }
                    },
            )
        }

    fun addAgentSpend(
        month: String,
        usd: Double,
    ) = update { data -> data.copy(agentSpendUsd = data.agentSpendUsd + (month to (data.agentSpendUsd[month] ?: 0.0) + usd)) }

    private fun Rule.changedTo(changed: Rule) =
        changed.copy(
            id = id,
            enabled = enabled,
            pinned = pinned,
            proposedBy = proposedBy,
            replaces = replaces,
            createdAtMillis = createdAtMillis,
            lastRun = lastRun,
        )

    private fun edit(
        id: String,
        change: (Rule) -> Rule,
    ) = update { data -> data.copy(rules = data.rules.map { if (it.id == id) change(it) else it }) }

    /** The data with each credential value stored in [secrets] and replaced by a reference; secrets no rule uses are removed. */
    private fun protect(
        data: AutomationData,
        before: AutomationData,
    ): AutomationData {
        val store = secrets ?: return data
        val rules =
            data.rules.map { rule ->
                rule.mapCredentials { path, key, value ->
                    if (value.isBlank() || value.startsWith(SECRET_REF)) {
                        value
                    } else {
                        val name = secretName(rule.id, path, key)
                        store.put(name, value)
                        SECRET_REF + name
                    }
                }
            }
        val kept = referencesIn(AutomationData(rules = rules))
        referencesIn(before).filterNot { it in kept }.forEach(store::remove)
        return data.copy(rules = rules)
    }

    /** The data with each reference replaced by its value, so the rest of the app never sees a reference. */
    private fun open(data: AutomationData): AutomationData {
        val store = secrets ?: return data
        return data.copy(
            rules =
                data.rules.map { rule ->
                    rule.mapCredentials { _, _, value ->
                        if (value.startsWith(SECRET_REF)) store.get(value.removePrefix(SECRET_REF)).orEmpty() else value
                    }
                },
        )
    }

    private fun referencesIn(data: AutomationData): Set<String> =
        data.rules
            .flatMap { rule -> rule.paramValues() }
            .filter { it.startsWith(SECRET_REF) }
            .map { it.removePrefix(SECRET_REF) }
            .toSet()

    private fun secretName(
        ruleId: String,
        path: String,
        key: String,
    ): String = "rule_${ruleId}_${path}_$key".replace(Regex("[^A-Za-z0-9_]"), "_")

    private fun load(): AutomationData =
        persistence
            .load()
            ?.let { runCatching { AutomationJson.json.decodeFromString(AutomationData.serializer(), it) }.getOrNull() }
            ?: AutomationData()

    private fun keptRuns(runs: List<RunRecord>): List<RunRecord> {
        var total = 0
        return runs.take(MAX_RUNS).takeWhile { run ->
            total += run.lines.sumOf { it.length } + run.variables.entries.sumOf { it.key.length + it.value.length }
            total <= MAX_HISTORY_CHARS
        }
    }

    companion object {
        /** Marks a parameter whose value is kept in the secret store; the rest of it is the secret's name. */
        const val SECRET_REF = "@secret:"

        const val MAX_RUNS = 200
        const val MAX_HISTORY_CHARS = 256 * 1024
    }
}

/** The rule's settings with each credential value passed through [transform], given its step path, key and value. */
internal fun Rule.mapCredentials(transform: (path: String, key: String, value: String) -> String): Rule {
    fun steps(
        list: List<ActionStep>,
        prefix: String,
    ): List<ActionStep> =
        list.mapIndexed { index, step ->
            val path = "$prefix$index"
            step.copy(
                params = step.params.mapValues { (key, value) -> if (isCredentialKey(key)) transform(path, key, value) else value },
                children = steps(step.children, "$path."),
                elseChildren = steps(step.elseChildren, "$path.else."),
            )
        }
    return copy(actions = steps(actions, ""))
}

/** Every parameter value in the rule's steps, nested ones included. */
internal fun Rule.paramValues(): List<String> {
    fun steps(list: List<ActionStep>): List<String> =
        list.flatMap { step ->
            step.params.values + steps(step.children) +
                steps(step.elseChildren)
        }
    return steps(actions)
}
