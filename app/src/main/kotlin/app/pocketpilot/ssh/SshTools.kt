package app.pocketpilot.ssh

import app.pocketpilot.core.model.Availability
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
import app.pocketpilot.network.ssh.SshServer
import app.pocketpilot.network.ssh.SshServers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

private fun schema(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

private fun JsonObject.text(name: String): String = (this[name] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

private fun SshServer.line(): String =
    buildString {
        append("$name: $user@${host.ifEmpty { "(found by MAC)" }}")
        if (port != SshServer.DEFAULT_PORT) append(":$port")
        if (mac.isNotEmpty()) append(", MAC $mac")
        append(if (usePassword) ", password" else ", PocketPilot's key")
        if (hostKey.isEmpty()) append(", host key not saved yet")
    }

/** `ssh.servers`: the SSH servers rules can use, and the public key PocketPilot signs in with. */
class SshServersTool(
    private val servers: SshServers,
) : ToolHandler {
    override val spec =
        ToolSpec(
            name = "ssh.servers",
            title = "List SSH servers",
            description =
                "Lists the SSH servers set up for the \"Run an SSH command\" rule action, and PocketPilot's public key, which " +
                    "each server needs in ~/.ssh/authorized_keys (for a comma device: in the owner's GitHub SSH keys).",
            inputSchema = schema("""{"type":"object","properties":{},"additionalProperties":false}"""),
            riskTier = RiskTier.READ,
            requiredScopes = setOf(Scope.DEVICE_READ),
            annotations = ToolAnnotations(readOnly = true),
            availability = Availability(Phase.P0),
        )

    override suspend fun execute(
        call: ToolCall,
        session: Session,
    ): ToolResult {
        val list = servers.servers.value
        val key = runCatching { servers.publicKey() }.getOrElse { "unavailable (${it.message})" }
        val text =
            buildString {
                append(if (list.isEmpty()) "No SSH servers yet." else list.joinToString("\n", "SSH servers:\n") { "- " + it.line() })
                append("\nPocketPilot's public key: ").append(key)
            }
        return ToolResult.success(text)
    }
}

/**
 * `ssh.add_server`: adds a server, or changes the one with that name. The owner confirms every call with the
 * server's details on screen. It never takes a password: those are typed in PocketPilot's settings.
 */
class SshAddServerTool(
    private val servers: SshServers,
) : ToolHandler {
    override val spec =
        ToolSpec(
            name = "ssh.add_server",
            title = "Add an SSH server",
            description =
                "Adds an SSH server for the \"Run an SSH command\" rule action, or changes the one with the same name; the " +
                    "owner confirms it. Give host (a name or address), mac (for a device on the phone's hotspot, whose address " +
                    "changes; find it with adb.query `ip neigh`), or both. It signs in with PocketPilot's key (see ssh.servers); " +
                    "passwords are set by the owner in Settings. A comma device uses user comma and port 22.",
            inputSchema =
                schema(
                    """{"type":"object","properties":{"name":{"type":"string"},"host":{"type":"string"},
                    "mac":{"type":"string","description":"aa:bb:cc:dd:ee:ff"},"user":{"type":"string"},
                    "port":{"type":"integer","minimum":1,"maximum":65535,"description":"Default 22"}},
                    "required":["name","user"],"additionalProperties":false}""",
                ),
            // It chooses where rules' commands go, so every call shows the owner exactly what is saved.
            riskTier = RiskTier.DESTRUCTIVE,
            requiredScopes = setOf(Scope.SHELL_EXEC),
            annotations = ToolAnnotations(destructive = true),
            availability = Availability(Phase.P0),
        )

    override suspend fun confirmationDetail(call: ToolCall): String {
        val (server, replaces) = proposed(call)
        return (if (replaces != null) "Change SSH server " else "Add SSH server ") + server.line()
    }

    override suspend fun execute(
        call: ToolCall,
        session: Session,
    ): ToolResult {
        val (server, replaces) = proposed(call)
        servers.save(server, null)?.let { throw ToolException(ToolErrorCode.INVALID_ARGUMENTS, it) }
        val saved = servers.find(server.name) ?: server
        return ToolResult.success((if (replaces != null) "Changed " else "Added ") + saved.line())
    }

    /** The server as it would be saved, and the one with that name it replaces, if any. */
    private fun proposed(call: ToolCall): Pair<SshServer, SshServer?> {
        val args = call.arguments
        val name = args.text("name")
        val existing = servers.find(name)?.takeIf { it.name.equals(name, ignoreCase = true) }
        val port = (args["port"] as? JsonPrimitive)?.intOrNull ?: args.text("port").toIntOrNull()
        val base = existing ?: SshServer(name = name, user = args.text("user"))
        val server =
            base.copy(
                name = name,
                host = args.text("host"),
                mac = args.text("mac"),
                user = args.text("user"),
                port = port ?: existing?.port ?: SshServer.DEFAULT_PORT,
            )
        return server to existing
    }
}
