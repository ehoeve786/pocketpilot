package app.pocketpilot.network.ssh

import android.content.Context
import app.pocketpilot.network.api.SecretStore
import app.pocketpilot.network.gonet.gen.ppnet.Ppnet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.util.UUID

/** A server rules can run commands on. Its password, if any, is in the secret store, not here. */
data class SshServer(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** A host name or address; may be empty when [mac] finds the server. */
    val host: String = "",
    /** The server's MAC address, for a device on the phone's hotspot whose address changes. */
    val mac: String = "",
    val port: Int = DEFAULT_PORT,
    val user: String,
    /** Sign in with a password instead of PocketPilot's key. */
    val usePassword: Boolean = false,
    /** The server's host key as `type base64`, saved on the first connection; a different key is refused. */
    val hostKey: String = "",
    /** The address the server answered on last, tried when the MAC address is not in the neighbor table. */
    val lastAddress: String = "",
) {
    companion object {
        const val DEFAULT_PORT = 22
    }
}

/** What a command printed and how it ended. */
data class SshOutput(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
)

class SshFailure(
    message: String,
) : RuntimeException(message)

/**
 * The SSH servers the owner set up, and the commands rules run on them. PocketPilot signs in with one ed25519 key
 * of its own, made on first use and kept in the encrypted [secrets]; the owner adds its public key to each server.
 * [neighbors] returns the phone's `ip neigh` output through privileged access, or null without it.
 */
class SshServers(
    context: Context,
    private val secrets: SecretStore,
    private val neighbors: suspend () -> String?,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Mutex()
    private val keyLock = Mutex()

    private val mutableServers = MutableStateFlow(read())
    val servers: StateFlow<List<SshServer>> = mutableServers.asStateFlow()

    /** The server called [nameOrId], ignoring case, or null. */
    fun find(nameOrId: String): SshServer? {
        val wanted = nameOrId.trim()
        return servers.value.firstOrNull { it.id == wanted } ?: servers.value.firstOrNull { it.name.equals(wanted, ignoreCase = true) }
    }

    /**
     * Adds or replaces [server]. [password] replaces the saved one when not null; an empty one removes it. A changed
     * address or port forgets the host key, since it may be another machine. Returns a problem in plain words, or null.
     */
    suspend fun save(
        server: SshServer,
        password: String?,
    ): String? =
        lock.withLock {
            val clean =
                server.copy(
                    name = server.name.trim(),
                    host = server.host.trim(),
                    mac = server.mac.trim().let { SshAddress.normalizeMac(it) ?: it },
                    user = server.user.trim(),
                )
            val problem =
                when {
                    clean.name.isEmpty() -> "Give the server a name"

                    servers.value.any {
                        it.id != clean.id &&
                            it.name.equals(
                                clean.name,
                                ignoreCase = true,
                            )
                    } -> "Another server has that name"

                    clean.host.isEmpty() && clean.mac.isEmpty() -> "Give a host name, an address or a MAC address"

                    clean.mac.isNotEmpty() && SshAddress.normalizeMac(
                        clean.mac,
                    ) == null -> "The MAC address should look like aa:bb:cc:dd:ee:ff"

                    clean.port !in 1..65535 -> "The port should be between 1 and 65535"

                    clean.user.isEmpty() -> "Give the user to sign in as"

                    clean.usePassword && password.isNullOrEmpty() && secrets.get(passwordKey(clean.id)) == null -> "Give the password"

                    else -> null
                }
            if (problem != null) return@withLock problem
            val old = servers.value.firstOrNull { it.id == clean.id }
            val moved = old != null && (old.host != clean.host || old.mac != clean.mac || old.port != clean.port)
            val saved = if (moved) clean.copy(hostKey = "", lastAddress = "") else clean
            if (password != null) {
                if (password.isEmpty()) secrets.remove(passwordKey(saved.id)) else secrets.put(passwordKey(saved.id), password)
            }
            if (!saved.usePassword) secrets.remove(passwordKey(saved.id))
            write(servers.value.filterNot { it.id == saved.id } + saved)
            null
        }

    suspend fun delete(id: String) =
        lock.withLock {
            secrets.remove(passwordKey(id))
            write(servers.value.filterNot { it.id == id })
        }

    /** Forgets the saved host key, for a server that was reinstalled; the next connection saves its new key. */
    suspend fun forgetHostKey(id: String) = update(id) { it.copy(hostKey = "") }

    /** PocketPilot's public key as an `authorized_keys` line, made the first time it is asked for. */
    suspend fun publicKey(): String = withContext(Dispatchers.IO) { Ppnet.sshPublicKey(privateKey()) }

    /**
     * Runs [command] on the server called [nameOrId] and returns what it printed. Fails with [SshFailure] when the
     * server is unknown, cannot be found or reached, refuses the sign-in, or shows a different host key.
     */
    suspend fun run(
        nameOrId: String,
        command: String,
        timeoutMs: Long,
    ): SshOutput {
        val server = find(nameOrId) ?: throw SshFailure("No SSH server called \"$nameOrId\"; add it in Settings, SSH servers")
        val password =
            if (server.usePassword) {
                secrets.get(passwordKey(server.id))
                    ?: throw SshFailure("No password saved for ${server.name}")
            } else {
                ""
            }
        val key = if (server.usePassword) "" else privateKey()
        val addresses = addressesOf(server)
        if (addresses.isEmpty()) throw SshFailure(notFound(server))
        var failure: Exception? = null
        for (address in addresses) {
            val result =
                try {
                    withContext(Dispatchers.IO) {
                        Ppnet.sshExec(
                            address,
                            server.port.toLong(),
                            server.user,
                            key,
                            password,
                            server.hostKey,
                            command,
                            timeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS),
                        )
                    }
                } catch (e: Exception) {
                    // A refused sign-in or a changed host key is the same on every address; only a failed dial moves on.
                    if (!e.isDialFailure()) throw SshFailure("${server.name}: ${e.message}")
                    failure = e
                    continue
                }
            update(server.id) { saved ->
                saved.copy(hostKey = saved.hostKey.ifEmpty { result.hostKey }, lastAddress = address)
            }
            return SshOutput(result.exitCode.toInt(), result.stdout, result.stderr, result.timedOut)
        }
        throw SshFailure("${server.name} did not answer on ${addresses.joinToString()}: ${failure?.message}")
    }

    /**
     * Where to try the server: the address its MAC address has now, then its host name resolved by Android (which
     * knows `.local` names and private DNS, unlike Go's resolver here), then where it answered last.
     */
    private suspend fun addressesOf(server: SshServer): List<String> =
        withContext(Dispatchers.IO) {
            val fromMac = server.mac.takeIf(String::isNotEmpty)?.let { mac -> neighbors()?.let { SshAddress.fromNeighbors(it, mac) } }
            val fromHost =
                server.host.takeIf(String::isNotEmpty)?.let { host ->
                    runCatching { InetAddress.getByName(host).hostAddress }.getOrNull()
                }
            listOfNotNull(fromMac, fromHost, server.lastAddress.takeIf(String::isNotEmpty)).distinct()
        }

    private fun notFound(server: SshServer): String =
        when {
            server.host.isNotEmpty() -> {
                "Could not find ${server.host}: check the name and that the phone is on its network"
            }

            else -> {
                "Could not find ${server.mac} near the phone. Finding a server by MAC address needs privileged access " +
                    "(Shizuku or PocketPilot's own), and the server connected to the phone's hotspot"
            }
        }

    private fun Exception.isDialFailure(): Boolean = message.orEmpty().startsWith(DIAL_PREFIX)

    private suspend fun privateKey(): String =
        keyLock.withLock {
            secrets.get(KEY) ?: withContext(Dispatchers.IO) { Ppnet.sshGenerateKey() }.also { secrets.put(KEY, it) }
        }

    private suspend fun update(
        id: String,
        change: (SshServer) -> SshServer,
    ) = lock.withLock {
        write(servers.value.map { if (it.id == id) change(it) else it })
    }

    private fun write(list: List<SshServer>) {
        val json = JSONArray()
        list.forEach { s ->
            json.put(
                JSONObject()
                    .put("id", s.id)
                    .put("name", s.name)
                    .put("host", s.host)
                    .put("mac", s.mac)
                    .put("port", s.port)
                    .put("user", s.user)
                    .put("usePassword", s.usePassword)
                    .put("hostKey", s.hostKey)
                    .put("lastAddress", s.lastAddress),
            )
        }
        prefs.edit().putString(KEY_SERVERS, json.toString()).apply()
        mutableServers.value = list
    }

    private fun read(): List<SshServer> {
        val json = runCatching { JSONArray(prefs.getString(KEY_SERVERS, null) ?: return emptyList()) }.getOrNull() ?: return emptyList()
        return (0 until json.length()).mapNotNull { i ->
            val o = json.optJSONObject(i) ?: return@mapNotNull null
            SshServer(
                id = o.optString("id").ifEmpty { return@mapNotNull null },
                name = o.optString("name"),
                host = o.optString("host"),
                mac = o.optString("mac"),
                port = o.optInt("port", SshServer.DEFAULT_PORT),
                user = o.optString("user"),
                usePassword = o.optBoolean("usePassword"),
                hostKey = o.optString("hostKey"),
                lastAddress = o.optString("lastAddress"),
            )
        }
    }

    private fun passwordKey(id: String) = "ssh.password.$id"

    private companion object {
        const val PREFS = "ssh_servers"
        const val KEY_SERVERS = "servers"
        const val KEY = "ssh.key"

        /** How [Ppnet.sshExec] starts the message of a connection that never reached the server. */
        const val DIAL_PREFIX = "could not reach"
        const val MIN_TIMEOUT_MS = 1_000L
        const val MAX_TIMEOUT_MS = 600_000L
    }
}
