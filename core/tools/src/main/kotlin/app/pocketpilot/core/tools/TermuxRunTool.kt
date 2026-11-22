package app.pocketpilot.core.tools

import app.pocketpilot.capability.api.termux.TermuxCommands
import app.pocketpilot.capability.api.termux.TermuxException
import app.pocketpilot.capability.api.termux.TermuxOutput
import app.pocketpilot.core.model.Availability
import app.pocketpilot.core.model.CapabilityId
import app.pocketpilot.core.model.ContentPart
import app.pocketpilot.core.model.Flavor
import app.pocketpilot.core.model.Phase
import app.pocketpilot.core.model.RiskTier
import app.pocketpilot.core.model.Scope
import app.pocketpilot.core.model.Session
import app.pocketpilot.core.model.ToolAnnotations
import app.pocketpilot.core.model.ToolCall
import app.pocketpilot.core.model.ToolErrorCode
import app.pocketpilot.core.model.ToolException
import app.pocketpilot.core.model.ToolResult
import app.pocketpilot.core.model.ToolSpec
import app.pocketpilot.core.orchestrator.ToolHandler
import kotlinx.serialization.json.JsonObject

/**
 * `termux.run`: one command with bash in Termux, for what needs Termux's own packages and files (Python, git, its
 * scripts). Behind the Shell commands switch like `shell.exec` ([enabled]), and approved once per exact command.
 */
class TermuxRunTool(
    private val termux: TermuxCommands,
    private val enabled: (Session) -> Boolean,
) : ToolHandler {
    override val spec =
        ToolSpec(
            name = "termux.run",
            title = "Run Termux command",
            description =
                "Runs one command with bash in Termux's home folder, with Termux's packages (python, git, ssh) and the " +
                    "owner's scripts, which shell.exec cannot reach. Waits for it and returns stdout, stderr and the exit " +
                    "code, waiting up to timeoutMs (10 minutes at most). Give a job that ends within that one call with a " +
                    "long timeoutMs rather than starting it and checking on it. For a longer job, pass wait=false with " +
                    "its output sent to a file (command > ~/job.log 2>&1), then make one call that waits for it to end " +
                    "and prints the log, such as: while pgrep -f job >/dev/null; do sleep 5; done; cat ~/job.log, " +
                    "with timeoutMs 600000, instead of polling in short calls. Shared storage is /sdcard (after " +
                    "termux-setup-storage). The owner approves each new command once.",
            inputSchema =
                schema(
                    """{"type":"object","properties":{"command":{"type":"string"},"wait":{"type":"boolean",
                    "description":"Default true; false starts it in the background and returns at once"},
                    "timeoutMs":{"type":"integer","minimum":1000,"maximum":600000,"description":"Default 60000"}},
                    "required":["command"],"additionalProperties":false}""",
                ),
            // As shell.exec: the owner approves each exact command once per session.
            riskTier = RiskTier.SENSITIVE,
            requiredScopes = setOf(Scope.TERMUX_RUN),
            requiredCapabilities = setOf(CapabilityId.RUN_TERMUX),
            annotations = ToolAnnotations(destructive = true, openWorld = true),
            availability = Availability(Phase.P0, setOf(Flavor.OSS, Flavor.PRO)),
        )

    override val timeoutMs: Long = MAX_TIMEOUT_MS + 5_000

    override suspend fun confirmationDetail(call: ToolCall): String? = call.arguments.string("command")

    override suspend fun execute(
        call: ToolCall,
        session: Session,
    ): ToolResult {
        if (!enabled(session)) {
            throw ToolException(
                ToolErrorCode.CAPABILITY_UNAVAILABLE,
                "Termux commands are off for this client",
                "Ask the owner to turn on Shell commands in PocketPilot, and for a remote connection also its " +
                    "\"Also for remote clients\" switch",
            )
        }
        val command = call.arguments.string("command")?.takeIf(String::isNotBlank) ?: invalid("Give command")
        val wait = call.arguments.boolean("wait") ?: true
        val timeout = (call.arguments.long("timeoutMs") ?: DEFAULT_TIMEOUT_MS).coerceIn(1_000, MAX_TIMEOUT_MS)
        val result =
            try {
                termux.run(command, wait, timeout)
            } catch (e: TermuxException) {
                throw ToolException(
                    ToolErrorCode.CAPABILITY_UNAVAILABLE,
                    e.message ?: "Termux did not run the command",
                    "Termux must be installed with allow-external-apps = true in ~/.termux/termux.properties; for a long " +
                        "job, use wait=false and write its output to a file",
                )
            } ?: return ToolResult.success("Started in Termux in the background; its output goes wherever the command sends it")
        return ToolResult(
            content = listOf(ContentPart.Text(text(result))),
            structured = compactJson.encodeToJsonElement(TermuxOutput.serializer(), result) as? JsonObject,
            isError = result.exitCode != 0,
        )
    }

    private fun text(result: TermuxOutput) =
        buildString {
            append("Exit code ${result.exitCode}")
            if (result.stdout.isNotEmpty()) append("\nstdout:\n").append(result.stdout.trimEnd())
            if (result.stderr.isNotEmpty()) append("\nstderr:\n").append(result.stderr.trimEnd())
        }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 60_000L
        const val MAX_TIMEOUT_MS = 600_000L
    }
}
