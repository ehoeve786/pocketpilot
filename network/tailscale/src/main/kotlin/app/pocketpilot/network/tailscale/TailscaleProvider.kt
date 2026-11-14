package app.pocketpilot.network.tailscale

import android.content.Context
import android.os.Build
import android.util.Log
import app.pocketpilot.network.api.NetworkProvider
import app.pocketpilot.network.api.NetworkState
import app.pocketpilot.network.api.PublicAccess
import app.pocketpilot.network.api.PublicAccessState
import app.pocketpilot.network.api.PublicAutoOff
import app.pocketpilot.network.api.StatusPoll
import app.pocketpilot.network.gonet.gen.ppnet.Node
import app.pocketpilot.network.gonet.gen.ppnet.Ppnet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * Tailscale through an embedded tsnet node (spec section 7). The node joins the owner's tailnet as
 * `pocketpilot-<model>`, terminates HTTPS with its `*.ts.net` certificate and forwards each request to
 * [targetPort] on 127.0.0.1. Public access is Tailscale Funnel on port 443.
 *
 * Whether Tailscale and Funnel are on survives restarts, so the foreground service can call [resume].
 * Funnel turns itself off after [autoOff]'s minutes without requests.
 */
class TailscaleProvider(
    context: Context,
    private val scope: CoroutineScope,
    private val targetPort: Int,
    private val autoOff: PublicAutoOff = PublicAutoOff(),
) : NetworkProvider,
    PublicAccess {
    override val id: String = "tailscale"

    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val stateDir = File(context.noBackupFilesDir, "tailscale")

    init {
        autoOff.minutes = prefs.getInt(KEY_AUTO_OFF_MINUTES, autoOff.minutes)
    }

    /** Created on first [start]: loading the Go library is the first thing that can fail. */
    @Volatile private var createdNode: Node? = null

    private val mutableState = MutableStateFlow(previousCrash())
    override val state: StateFlow<NetworkState> = mutableState.asStateFlow()

    private val mutablePublic =
        MutableStateFlow(PublicAccessState(enabled = prefs.getBoolean(KEY_PUBLIC, false), autoOffMinutes = autoOff.minutes))
    override val publicState: StateFlow<PublicAccessState> = mutablePublic.asStateFlow()

    /** Whether the owner turned Tailscale on. */
    val enabled: Boolean get() = prefs.getBoolean(KEY_ENABLED, false)

    private var poller: Job? = null
    private val poll = StatusPoll()

    // One node call at a time, in the order made, so a stop queued before a start cannot land after it.
    private val nodeDispatcher = Dispatchers.IO.limitedParallelism(1)

    /** Starts the node if the owner left it on. */
    fun resume() {
        if (enabled) start()
    }

    @Synchronized
    override fun start() {
        prefs.edit().putBoolean(KEY_ENABLED, true).apply()
        // The poller ends when the node stops, so a live one means a start is under way, unless it failed and needs a retry.
        if (poller?.isActive == true && state.value !is NetworkState.Error) return
        poller?.cancel()
        autoOff.restart(System.currentTimeMillis())
        mutableState.value = NetworkState.Connecting
        poller =
            scope.launch(nodeDispatcher) {
                try {
                    val node = node()
                    node.setPublic(mutablePublic.value.enabled)
                    // Start does nothing on a node that is still up, so a retry after an error needs it stopped first.
                    node.stop()
                    node.start("")
                } catch (e: Throwable) {
                    // Errors too: a missing native library is an UnsatisfiedLinkError. The stack trace goes to logcat only.
                    Log.e(TAG, "Tailscale failed to start", e)
                    mutableState.value = NetworkState.Error(e.message ?: e.toString())
                    return@launch
                }
                while (isActive) {
                    val before = state.value to mutablePublic.value
                    if (!refresh()) break
                    // The status call into the node and its JSON cost battery; a link that stays up is read less often.
                    poll.await(connected = state.value is NetworkState.Connected, changed = (state.value to mutablePublic.value) != before)
                }
            }
    }

    @Synchronized
    override fun stop() {
        // Public access rides on this node, so turning Tailscale off also turns public access off for good.
        prefs
            .edit()
            .putBoolean(KEY_ENABLED, false)
            .putBoolean(KEY_PUBLIC, false)
            .apply()
        poller?.cancel()
        poller = null
        scope.launch(nodeDispatcher) { runCatching { createdNode?.stop() } }
        mutableState.value = NetworkState.Stopped
        mutablePublic.value = PublicAccessState(enabled = false, autoOffMinutes = autoOff.minutes)
    }

    /** Stops the node when the foreground service ends, but keeps it on for the next [resume]. */
    @Synchronized
    fun pause() {
        if (poller == null) return
        poller?.cancel()
        poller = null
        scope.launch(nodeDispatcher) { runCatching { createdNode?.stop() } }
        mutableState.value = NetworkState.Stopped
    }

    override fun logout() {
        // Queued before stop's job: Logout needs the node still running to sign out, and stops it itself.
        scope.launch(nodeDispatcher) { runCatching { createdNode?.logout() } }
        stop()
    }

    override fun setPublic(enabled: Boolean) = applyPublic(enabled, autoOffAfterMinutes = null)

    override fun setAutoOffMinutes(minutes: Int) {
        require(minutes >= 0)
        prefs.edit().putInt(KEY_AUTO_OFF_MINUTES, minutes).apply()
        autoOff.minutes = minutes
        // A shorter limit counts from now, so choosing it does not turn public access off at once.
        autoOff.restart(System.currentTimeMillis())
        mutablePublic.value = mutablePublic.value.copy(autoOffMinutes = minutes)
    }

    private fun applyPublic(
        enabled: Boolean,
        autoOffAfterMinutes: Int?,
    ) {
        prefs.edit().putBoolean(KEY_PUBLIC, enabled).apply()
        if (enabled) autoOff.restart(System.currentTimeMillis())
        mutablePublic.value = PublicAccessState(enabled, autoOffAfterMinutes = autoOffAfterMinutes, autoOffMinutes = autoOff.minutes)
        scope.launch(nodeDispatcher) {
            runCatching { createdNode?.setPublic(enabled) }
            refresh()
            // Funnel takes a moment to start serving, so the poller goes back to reading often.
            poll.poke()
        }
    }

    /** The node's recent log lines, for Doctor. */
    fun logs(): String = runCatching { createdNode?.logs() }.getOrNull().orEmpty()

    @Synchronized
    private fun node(): Node =
        createdNode ?: run {
            go.Seq.setContext(appContext)
            Ppnet.newNode(stateDir.absolutePath, hostname(), targetPort.toLong()).also { createdNode = it }
        }

    /**
     * If the Go runtime died in the last run, it left a report in the state directory. Shows it once,
     * and leaves Tailscale off so [resume] does not crash the app again on every launch.
     */
    private fun previousCrash(): NetworkState {
        val crash = runCatching { File(stateDir, CRASH_FILE).readText() }.getOrNull()
        if (crash.isNullOrBlank()) return NetworkState.Stopped
        val log = runCatching { File(stateDir, LOG_FILE).readLines().takeLast(CRASH_LOG_LINES) }.getOrDefault(emptyList())
        runCatching { File(stateDir, CRASH_FILE).writeText("") }
        prefs.edit().putBoolean(KEY_ENABLED, false).apply()
        val details = crash.trim() + "\n\n--- Tailscale log ---\n" + log.joinToString("\n")
        return NetworkState.Error("Tailscale stopped unexpectedly last time", details)
    }

    /** Returns false once the node is no longer running. */
    private suspend fun refresh(): Boolean {
        val node = createdNode ?: return true
        val status = runCatching { JSONObject(node.status()) }.getOrNull() ?: return true
        // A cancelled poller's status call can return after a new start; its result is stale.
        if (!currentCoroutineContext().isActive) return false
        val backend = status.optString("backendState")
        val dnsName = status.optString("dnsName")
        val error = status.optString("error").takeIf(String::isNotEmpty)
        val ips = status.optJSONArray("ips")
        mutableState.value =
            when {
                !status.optBoolean("running") -> {
                    NetworkState.Stopped
                }

                backend == "NeedsLogin" && status.optString("authUrl").isNotEmpty() -> {
                    NetworkState.NeedsAuth(status.optString("authUrl"))
                }

                backend == "NeedsMachineAuth" -> {
                    NetworkState.Error("Approve this phone in the Tailscale admin console, under Machines")
                }

                backend == "Running" && dnsName.isNotEmpty() -> {
                    NetworkState.Connected(ips?.optString(0).orEmpty(), dnsName)
                }

                error != null -> {
                    NetworkState.Error(error)
                }

                else -> {
                    NetworkState.Connecting
                }
            }
        val public = status.optBoolean("public")
        val lastRequestMs = status.optLong("lastRequestUnix") * MS_PER_SECOND
        if (public && autoOff.expired(System.currentTimeMillis(), lastRequestMs)) {
            applyPublic(false, autoOffAfterMinutes = autoOff.minutes)
            return true
        }
        val funnelProblem = status.optString("funnelProblem").takeIf(String::isNotEmpty)
        // The node gave up on Funnel and says why. The owner's choice is cleared with it, so the next start does not
        // ask for public access again behind a screen that shows it off; the owner turns it on once it can work.
        // A general error, such as no network after a reboot, is not Funnel failing and keeps the choice.
        if (!public && funnelProblem != null && prefs.getBoolean(KEY_PUBLIC, false)) {
            prefs.edit().putBoolean(KEY_PUBLIC, false).apply()
        }
        val problem = funnelProblem ?: error.takeIf { public }
        mutablePublic.value =
            PublicAccessState(
                enabled = public,
                url = if (public && problem == null && status.optBoolean("serving")) "https://$dnsName/mcp" else null,
                problem = problem,
                autoOffAfterMinutes = mutablePublic.value.autoOffAfterMinutes.takeUnless { public },
                autoOffMinutes = autoOff.minutes,
            )
        return status.optBoolean("running")
    }

    private companion object {
        const val TAG = "Tailscale"
        const val PREFS = "network_tailscale"
        const val KEY_ENABLED = "enabled"
        const val KEY_PUBLIC = "public"
        const val KEY_AUTO_OFF_MINUTES = "public_auto_off_minutes"
        const val CRASH_LOG_LINES = 80
        const val MS_PER_SECOND = 1_000L

        // ppnet.CrashFile and ppnet.LogFile. Kept here so reading them never loads the Go library.
        const val CRASH_FILE = "crash.txt"
        const val LOG_FILE = "node.log"

        /** `pocketpilot-sm-s918w`: Tailscale host names allow letters, digits and hyphens. */
        fun hostname(): String =
            "pocketpilot-" +
                Build.MODEL
                    .lowercase()
                    .replace(Regex("[^a-z0-9]+"), "-")
                    .trim('-')
                    .take(MAX_MODEL_LENGTH)

        const val MAX_MODEL_LENGTH = 40
    }
}
