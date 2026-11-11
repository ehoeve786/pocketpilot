package app.pocketpilot.network.wireguard

import android.content.Context
import android.util.Log
import app.pocketpilot.network.api.NetworkProvider
import app.pocketpilot.network.api.NetworkState
import app.pocketpilot.network.api.SecretStore
import app.pocketpilot.network.api.StatusPoll
import app.pocketpilot.network.certificates.CertificateManager
import app.pocketpilot.network.gonet.gen.ppnet.Ppnet
import app.pocketpilot.network.gonet.gen.ppnet.Tunnel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.InetAddress

/** What the owner set up for WireGuard, without the private key. */
data class WireGuardSetup(
    val configured: Boolean = false,
    /** The phone's public key, for the server's peer list. */
    val publicKey: String? = null,
    /** The server's host from the config's Endpoint, which is also the relay. */
    val serverHost: String? = null,
    /** The newest handshake with the server, in Unix milliseconds, or 0. */
    val lastHandshake: Long = 0,
)

/**
 * WireGuard in userspace on its own network stack in the app (spec section 7): no VpnService, so
 * the rest of the phone's traffic is untouched. The owner imports a wg-quick config, typically
 * exported by their router's WireGuard server. The phone listens for HTTPS on port 443 of its tunnel
 * address with the [certificates] certificate, and a relay (the router or a server) forwards the
 * domain's port 443 to it.
 */
class WireGuardProvider(
    context: Context,
    private val scope: CoroutineScope,
    private val secrets: SecretStore,
    private val certificates: CertificateManager,
    private val targetPort: Int,
) : NetworkProvider {
    override val id: String = "wireguard"

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile private var createdTunnel: Tunnel? = null

    private val mutableState = MutableStateFlow<NetworkState>(NetworkState.Stopped)
    override val state: StateFlow<NetworkState> = mutableState.asStateFlow()

    private val mutableSetup = MutableStateFlow(setupFrom(secrets.get(SECRET_CONFIG)))
    val setup: StateFlow<WireGuardSetup> = mutableSetup.asStateFlow()

    val enabled: Boolean get() = prefs.getBoolean(KEY_ENABLED, false)

    private var poller: Job? = null
    private val poll = StatusPoll(startingMs = StatusPoll.STARTING_MS * 3)

    /** The stop of the tunnel that [pause] began; a start waits for it, so the two cannot interleave. */
    private var stopping: Job? = null

    init {
        // Deriving the public key loads the Go library, so it stays off the thread that builds this.
        scope.launch(Dispatchers.IO) {
            val config = secrets.get(SECRET_CONFIG) ?: return@launch
            mutableSetup.value = mutableSetup.value.copy(publicKey = publicKeyOf(config).getOrNull())
        }
    }

    /**
     * Checks and saves a wg-quick config. Returns a problem in plain words, or null when it is
     * usable. A running tunnel restarts with it.
     */
    fun configure(config: String): String? {
        val publicKey = publicKeyOf(config)
        publicKey.exceptionOrNull()?.let { return it.message ?: it.toString() }
        secrets.put(SECRET_CONFIG, config)
        mutableSetup.value = setupFrom(config).copy(publicKey = publicKey.getOrNull())
        if (poller?.isActive == true) {
            pause()
            start()
        }
        return null
    }

    fun resume() {
        if (enabled) start()
    }

    @Synchronized
    override fun start() {
        val config = secrets.get(SECRET_CONFIG) ?: return
        prefs.edit().putBoolean(KEY_ENABLED, true).apply()
        if (poller?.isActive == true) return
        mutableState.value = NetworkState.Connecting
        poller =
            scope.launch(Dispatchers.IO) {
                stopping?.join()
                // The server's name may not resolve yet, right after boot or with no network. That is tried again,
                // more slowly each time, instead of leaving the tunnel off until the owner switches it on again.
                var wait = START_RETRY_MS
                while (isActive) {
                    try {
                        tunnel().start(config)
                        break
                    } catch (e: Throwable) {
                        // The stack trace goes to logcat; the screen shows only what failed, not the code it failed in.
                        Log.e(TAG, "WireGuard failed to start", e)
                        mutableState.value = NetworkState.Error(e.message ?: e.toString())
                        delay(wait)
                        wait = (wait * 2).coerceAtMost(START_RETRY_MAX_MS)
                    }
                }
                val host = mutableSetup.value.serverHost
                var endpoint = host?.let(::addressesOf)
                var endpointCheckedAt = System.currentTimeMillis()
                var followedAt = 0L
                while (isActive) {
                    val before = state.value
                    refresh()
                    // Also catches a domain set after the tunnel came up, and a router whose address changed.
                    val now = System.currentTimeMillis()
                    if (state.value is NetworkState.Connected && now - followedAt > FOLLOW_RELAY_MS) {
                        followedAt = now
                        mutableSetup.value.serverHost?.let { certificates.followRelay(it) }
                    }
                    // The tunnel looks up the server's name once, when it starts. When the name now leads somewhere
                    // else, such as a home connection that got a new address, it is started again to follow it.
                    if (host != null && now - endpointCheckedAt > ENDPOINT_CHECK_MS) {
                        endpointCheckedAt = now
                        val current = addressesOf(host)
                        val moved = current != null && endpoint?.let { known -> known.intersect(current).isEmpty() } == true
                        if (current != null) endpoint = current
                        if (moved) {
                            Log.i(TAG, "The WireGuard server's address changed; restarting the tunnel")
                            runCatching {
                                tunnel().stop()
                                // Not started again when the owner paused or stopped the tunnel meanwhile.
                                if (isActive) tunnel().start(config)
                                if (!isActive) tunnel().stop()
                            }.onFailure { mutableState.value = NetworkState.Error(it.message ?: it.toString()) }
                        }
                    }
                    // A tunnel that stays up is read less often; the handshake time alone is not a change.
                    poll.await(connected = state.value is NetworkState.Connected, changed = state.value != before)
                }
            }
    }

    @Synchronized
    override fun stop() {
        prefs.edit().putBoolean(KEY_ENABLED, false).apply()
        pause()
    }

    /** Stops the tunnel when the foreground service ends, but keeps it on for the next [resume]. */
    @Synchronized
    fun pause() {
        poller?.cancel()
        poller = null
        stopping = scope.launch(Dispatchers.IO) { runCatching { createdTunnel?.stop() } }
        mutableState.value = NetworkState.Stopped
    }

    /** Forgets the config and its private key. */
    override fun logout() {
        stop()
        secrets.remove(SECRET_CONFIG)
        mutableSetup.value = WireGuardSetup()
    }

    fun logs(): String = runCatching { createdTunnel?.logs() }.getOrNull().orEmpty()

    @Synchronized
    private fun tunnel(): Tunnel = createdTunnel ?: Ppnet.newTunnel(targetPort.toLong(), certificates.native).also { createdTunnel = it }

    private fun refresh() {
        val status = runCatching { JSONObject(createdTunnel?.status() ?: return) }.getOrNull() ?: return
        val address = status.optJSONArray("addresses")?.optString(0).orEmpty()
        val handshake = status.optLong("lastHandshake")
        val error = status.optString("error").takeIf(String::isNotEmpty)
        mutableSetup.value = mutableSetup.value.copy(lastHandshake = handshake)
        val domain = certificates.domain
        mutableState.value =
            when {
                error != null -> NetworkState.Error(error)
                !status.optBoolean("running") -> NetworkState.Stopped
                handshake == 0L -> NetworkState.Connecting
                domain == null -> NetworkState.Error("Set your domain under Certificate so clients can reach the phone")
                else -> NetworkState.Connected(address, domain)
            }
    }

    /** The addresses [host] resolves to now; null when it cannot be looked up, empty never. */
    private fun addressesOf(host: String): Set<String>? =
        runCatching { InetAddress.getAllByName(host).mapNotNull { it.hostAddress }.toSet() }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }

    private fun setupFrom(config: String?): WireGuardSetup {
        if (config == null) return WireGuardSetup()
        return WireGuardSetup(configured = true, serverHost = serverHostOf(config))
    }

    private fun publicKeyOf(config: String): Result<String> =
        runCatching {
            // Sets up the Go library's Android context before the first call into it.
            certificates.native
            Ppnet.publicKeyOfConfig(config)
        }

    private companion object {
        const val TAG = "WireGuard"
        const val PREFS = "network_wireguard"
        const val KEY_ENABLED = "enabled"
        const val SECRET_CONFIG = "wireguard_config"
        const val FOLLOW_RELAY_MS = 15 * 60_000L
        const val ENDPOINT_CHECK_MS = 5 * 60_000L
        const val START_RETRY_MS = 15_000L
        const val START_RETRY_MAX_MS = 5 * 60_000L
    }
}
