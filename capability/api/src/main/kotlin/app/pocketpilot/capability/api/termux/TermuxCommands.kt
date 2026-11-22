package app.pocketpilot.capability.api.termux

import app.pocketpilot.capability.api.CapabilityBackend
import kotlinx.serialization.Serializable

/** RUN_TERMUX: one command with bash in Termux, with its packages and home folder (`termux.run`). */
interface TermuxCommands : CapabilityBackend {
    /**
     * Runs [command] with `bash -c` in Termux's home folder. With [wait], returns what it printed within [timeoutMs];
     * without, starts it in the background and returns null at once. Fails with [TermuxException].
     */
    suspend fun run(
        command: String,
        wait: Boolean,
        timeoutMs: Long,
    ): TermuxOutput?
}

@Serializable
data class TermuxOutput(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
)

/** Termux is missing, refused the command, or did not answer in time; the message says which, in plain words. */
class TermuxException(
    message: String,
) : RuntimeException(message)
