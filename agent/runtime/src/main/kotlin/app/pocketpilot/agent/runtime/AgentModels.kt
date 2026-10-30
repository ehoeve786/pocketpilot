package app.pocketpilot.agent.runtime

import app.pocketpilot.agent.providers.api.ModelProvider
import app.pocketpilot.core.audit.Redactor
import app.pocketpilot.core.model.Scope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Limits for one run (spec section 9, Budgets). A run stops when it reaches any of them. */
@Serializable
data class AgentBudgets(
    val maxSteps: Int = 40,
    /** Time the run spends working; time paused for a takeover does not count. */
    val maxWallTimeMs: Long = 10 * 60_000L,
    /** In US dollars; null means no limit, the default for local models. */
    val maxCostUsd: Double? = null,
    val maxImagesPerRequest: Int = 2,
)

/** How the model calls tools. */
enum class ToolMode {
    /** The provider's own tool calling. */
    NATIVE,

    /** The model writes `{"action": ..., "args": ...}` in its reply, for models without tool calling. */
    JSON_FALLBACK,
}

/** The model a run uses and what it can do, from the model registry. */
class RunModel(
    val provider: ModelProvider,
    val model: String,
    val toolMode: ToolMode = ToolMode.NATIVE,
    val vision: Boolean = false,
    /** Estimated cost in US dollars for the given token counts, or null when the price is unknown. */
    val estimateCost: (inputTokens: Long, outputTokens: Long) -> Double? = { _, _ -> null },
    val maxOutputTokens: Int = 4096,
    /**
     * The owner's next model, used for the rest of the run when every key of this one is rate limited,
     * out of credit or refused. Models that call tools differently are skipped.
     */
    val fallback: RunModel? = null,
    /** How the transcript names this model, such as the profile's name. */
    val label: String = model,
)

/** What the owner asked for and which scopes the run may use. */
data class AgentTask(
    val goal: String,
    val scopes: Set<Scope>,
    /** Sensitive tools the owner allowed without a prompt for this run, such as `settings.set`. */
    val preApproved: Set<String> = emptySet(),
    /**
     * A chat: after each reply the run waits for the owner's next message ([AgentRun.send]) instead of
     * ending. Automations run one-shot tasks.
     */
    val conversation: Boolean = false,
    /** Lines written into the run's log before its first step, such as a fallback model that was left out. */
    val notes: List<String> = emptyList(),
)

@Serializable
enum class RunStatus {
    RUNNING,

    /** The owner took over; the run waits for [AgentRun.resume]. */
    PAUSED,

    /** A chat answered the owner's last message and waits for the next one. */
    WAITING,

    /** The model said it was done. */
    FINISHED,

    /** The owner pressed Stop. */
    STOPPED,

    /** A budget ran out. */
    OUT_OF_BUDGET,

    /** The model or the tools failed too often, or the provider refused the request. */
    FAILED,
}

/** One tool call in a step, as stored in the transcript. */
@Serializable
data class StepCall(
    val tool: String,
    /** The arguments with typed text, file contents and secrets summarised; see [transcriptArgs]. */
    val argsJson: String,
    /** The first part of the tool's text result. */
    val resultSummary: String,
    val isError: Boolean,
)

/**
 * The arguments as the transcript keeps them. Typed text, file contents and secrets are summarised the way the audit
 * log summarises them, so the run files and copied transcripts never hold them. Arguments that do not parse are kept
 * only as a length, since they may hold anything.
 */
internal fun transcriptArgs(argsJson: String): String {
    if (argsJson.isBlank()) return argsJson
    val parsed = runCatching { Json.parseToJsonElement(argsJson) as? JsonObject }.getOrNull()
    if (parsed == null) return unparsedSummary(argsJson)
    return Redactor().redact(parsed).toString()
}

private fun unparsedSummary(text: String): String =
    buildJsonObject {
        put("redacted", true)
        put("length", text.length)
    }.toString()

/** One plan-act-observe step (spec section 9, Transcript). */
@Serializable
data class AgentStep(
    val index: Int,
    val atMillis: Long,
    val text: String? = null,
    val calls: List<StepCall> = emptyList(),
    /** dHash of the screen when the tools returned an image, as hex. */
    val screenHash: String? = null,
    /** A note from the runtime, such as a retry or the owner resuming. */
    val note: String? = null,
    /** In a chat, a message from the owner after the first. */
    val user: String? = null,
    /** In a chat, the agent's answer that ended a turn. */
    val reply: String? = null,
)

/** A run's live state, also what the transcript stores. */
@Serializable
data class RunState(
    val id: String,
    val goal: String,
    val provider: String,
    val model: String,
    val startedAtMillis: Long,
    val status: RunStatus = RunStatus.RUNNING,
    val steps: List<AgentStep> = emptyList(),
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    /** Estimated cost in US dollars, when the model's price is known. */
    val costUsd: Double? = null,
    /** The model's closing summary, or why the run ended otherwise. */
    val outcome: String? = null,
    val endedAtMillis: Long? = null,
    /** A chat rather than a one-shot task. */
    val conversation: Boolean = false,
) {
    /** Working on a task or turn; a waiting chat is not active. */
    val active: Boolean get() = status == RunStatus.RUNNING || status == RunStatus.PAUSED

    /** Not ended yet: working, or a chat waiting for the owner. */
    val open: Boolean get() = active || status == RunStatus.WAITING
}

/**
 * The stored chats a delete removes. [picked] null means every earlier chat. The chat on screen ([keep])
 * is never one of them, and task runs under Activity are left alone.
 */
fun chatsToDelete(
    history: List<RunState>,
    keep: String?,
    picked: Set<String>?,
): Set<String> =
    history
        .filter { it.conversation && it.id != keep && (picked == null || it.id in picked) }
        .mapTo(mutableSetOf(), RunState::id)

/** Keeps run transcripts; the app stores them on disk. */
interface RunStore {
    suspend fun save(run: RunState)
}
