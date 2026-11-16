package app.pocketpilot.capability.shizuku

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.provider.Settings
import app.pocketpilot.capability.api.shell.ShellReply
import app.pocketpilot.capability.shizuku.adb.AdbAuthException
import app.pocketpilot.capability.shizuku.adb.AdbClient
import app.pocketpilot.capability.shizuku.adb.AdbKey
import app.pocketpilot.capability.shizuku.adb.AdbMdns
import app.pocketpilot.capability.shizuku.adb.AdbPairingClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.io.IOException

/** Where PocketPilot's own privileged process stands, shown in Settings and Doctor. */
data class BuiltInStatus(
    val phase: Phase,
    /** What went wrong last, in words for the owner. */
    val problem: String? = null,
) {
    enum class Phase {
        /** Wireless debugging has not been paired with PocketPilot yet. */
        NOT_PAIRED,

        /** Paired, not running, and not trying right now. */
        STOPPED,
        STARTING,
        RUNNING,
    }
}

/**
 * Pairs PocketPilot with wireless debugging once, then starts [PrivilegedMain] through adb whenever it is not
 * running: when the app starts (after a boot or an update), when Wi-Fi comes back, and after a failure with a
 * growing wait. With TCP mode on, adb also listens on [TCP_PORT] until the next reboot, so a restart works
 * without Wi-Fi.
 */
class PrivilegedStarter(
    private val context: Context,
    private val connection: ShizukuConnection,
    private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val key by lazy { AdbKey(File(context.noBackupFilesDir, "adb/adbkey")) }
    private val mdns = AdbMdns(context)
    private val lock = Mutex()
    private var retry: Job? = null
    private var failures = 0

    private val mutableStatus = MutableStateFlow(BuiltInStatus(if (paired) BuiltInStatus.Phase.STOPPED else BuiltInStatus.Phase.NOT_PAIRED))
    val status: StateFlow<BuiltInStatus> = mutableStatus.asStateFlow()

    val paired: Boolean get() = prefs.getBoolean(KEY_PAIRED, false)

    /** Keep adb on [TCP_PORT] after a start, so the process can be restarted without Wi-Fi until the next reboot. */
    var tcpMode: Boolean
        get() = prefs.getBoolean(KEY_TCP, false)
        set(value) {
            prefs.edit().putBoolean(KEY_TCP, value).apply()
        }

    private var started = false

    /** Starts watching; call once from the app's process. */
    fun init() {
        if (started) return
        started = true
        scope.launch {
            connection.source.collect { source ->
                if (source == PrivilegedSource.BUILT_IN) {
                    failures = 0
                    retry?.cancel()
                    mutableStatus.value = BuiltInStatus(BuiltInStatus.Phase.RUNNING)
                } else if (paired && mutableStatus.value.phase == BuiltInStatus.Phase.RUNNING) {
                    // It stopped (or PocketPilot was updated): start it again.
                    mutableStatus.value = BuiltInStatus(BuiltInStatus.Phase.STOPPED)
                    schedule(RESTART_DELAY_MS)
                }
            }
        }
        // A process that survived the app's own restart hands its binder over within a few seconds.
        if (paired) schedule(FIRST_START_DELAY_MS)
        // After a reboot Android may ask to allow wireless debugging on this Wi-Fi, which cannot be answered while the
        // phone is locked; as the Shizuku fork does, the start is tried again as soon as the owner unlocks.
        val unlocked =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    if (paired && !running() && mutableStatus.value.phase != BuiltInStatus.Phase.STARTING) {
                        failures = 0
                        schedule(UNLOCK_DELAY_MS)
                    }
                }
            }
        val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(unlocked, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(unlocked, filter)
        }
        context.getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (paired && !running()) schedule(WIFI_DELAY_MS)
                }
            },
        )
    }

    /**
     * Pairs with the code shown under Wireless debugging, Pair device with pairing code, then starts. [input] is
     * the six-digit code, optionally with the port from the same dialog ("246994 37611" or "...:37611"), which
     * skips looking the port up on the network.
     */
    suspend fun pair(input: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val (code, typedPort) = PairingInput.parse(input)
                val port =
                    typedPort
                        ?: mdns.find(AdbMdns.TLS_PAIRING, DISCOVERY_TIMEOUT_MS)
                        ?: throw IOException(
                            "${mdns.lastProblem}. Type the code and the port shown under it, for example 123456 37611",
                        )
                AdbPairingClient(LOCALHOST, port, code, key).use { it.pair() }
                prefs.edit().putBoolean(KEY_PAIRED, true).apply()
                mutableStatus.value = BuiltInStatus(BuiltInStatus.Phase.STOPPED)
            }.onSuccess { scope.launch { start() } }
        }

    /** Forgets the pairing and stops the process; Shizuku is used again if it is there. */
    fun forget() {
        prefs.edit().putBoolean(KEY_PAIRED, false).apply()
        retry?.cancel()
        if (connection.source.value == PrivilegedSource.BUILT_IN) runCatching { connection.service().destroy() }
        PrivilegedToken.renew(context)
        mutableStatus.value = BuiltInStatus(BuiltInStatus.Phase.NOT_PAIRED)
    }

    /** Starts the process now unless it runs; returns what went wrong. */
    suspend fun start(): Result<Unit> =
        lock.withLock {
            if (running()) return@withLock Result.success(Unit)
            if (!paired) return@withLock Result.failure(IllegalStateException("Pair wireless debugging with PocketPilot first"))
            mutableStatus.value = BuiltInStatus(BuiltInStatus.Phase.STARTING)
            val result = runCatching { launch() }
            // Being cancelled is not a failed start: it is passed on, not shown as the reason.
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            if (result.isSuccess) {
                failures = 0
                mutableStatus.value = BuiltInStatus(BuiltInStatus.Phase.RUNNING)
            } else {
                failures++
                val problem = result.exceptionOrNull()?.message ?: "Could not start"
                mutableStatus.value = BuiltInStatus(BuiltInStatus.Phase.STOPPED, problem)
                schedule(RETRY_DELAYS_MS[minOf(failures, RETRY_DELAYS_MS.size) - 1])
            }
            result
        }

    private fun running() = connection.source.value == PrivilegedSource.BUILT_IN

    /**
     * Starts after [delayMs], replacing a start that is still waiting. Only the wait can be cancelled: a start
     * already under way runs in its own coroutine, so Wi-Fi coming back or another trigger cannot cut it off.
     */
    private fun schedule(delayMs: Long) {
        retry?.cancel()
        retry =
            scope.launch {
                delay(delayMs)
                scope.launch { if (paired && !running()) start() }
            }
    }

    private suspend fun launch() {
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val canWrite = context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
            val wifiWasOn = Settings.Global.getInt(resolver, ADB_WIFI_ENABLED, 0) == 1
            try {
                connect(canWrite, wifiWasOn).use { client ->
                    val output = StringBuilder()
                    client.run("shell:${command(PrivilegedToken.renew(context))}") { chunk ->
                        if (output.length < MAX_OUTPUT) output.append(String(chunk))
                    }
                    if (STARTED_MARK !in output) throw IOException("adb did not start PocketPilot: ${output.toString().trim()}")
                }
                // Wireless debugging stays on until the process has connected, so turning it off cannot catch it
                // before it has left adb's session.
                withTimeoutOrNull(BINDER_TIMEOUT_MS) { connection.source.first { it == PrivilegedSource.BUILT_IN } }
                    ?: throw IOException("PocketPilot's privileged process started but did not connect")
                if (canWrite) {
                    // Paired keys that go unused for a week are dropped unless this is off.
                    Settings.Global.putLong(resolver, ADB_ALLOWED_CONNECTION_TIME, 0L)
                }
            } finally {
                // Wireless debugging is left as the owner had it; some apps refuse to run while it is on.
                if (canWrite && !wifiWasOn) Settings.Global.putInt(resolver, ADB_WIFI_ENABLED, 0)
            }
        }
    }

    /** An authenticated adb connection: TCP mode's port first, then wireless debugging's port from mDNS. */
    private suspend fun connect(
        canWrite: Boolean,
        wifiWasOn: Boolean,
    ): AdbClient {
        if (tcpMode) tryConnect(TCP_PORT)?.let { return it }
        val resolver = context.contentResolver
        if (!wifiWasOn) {
            if (!canWrite) throw IOException("Turn on Wireless debugging in Developer options")
            if (Settings.Global.getInt(resolver, Settings.Global.ADB_ENABLED, 0) == 0) {
                Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, 1)
            }
            Settings.Global.putInt(resolver, ADB_WIFI_ENABLED, 1)
            // On a Wi-Fi network wireless debugging has not been allowed on, Android asks and switches it back off.
            delay(AUTHORIZE_WAIT_MS)
            if (Settings.Global.getInt(resolver, ADB_WIFI_ENABLED, 0) == 0) {
                throw IOException("Wireless debugging is not allowed on this Wi-Fi; turn it on once and tap Always allow on this network")
            }
        }
        val port =
            mdns.find(AdbMdns.TLS_CONNECT, DISCOVERY_TIMEOUT_MS)
                ?: portFromProperty()
                ?: portFromShizuku()
                ?: throw IOException("${mdns.lastProblem}; Wireless debugging needs Wi-Fi")
        val client = AdbClient(LOCALHOST, port, key)
        try {
            client.connect()
        } catch (e: AdbAuthException) {
            client.close()
            throw e
        }
        if (!tcpMode) return client
        // adbd restarts on its TCP port; the wireless debugging connection ends with it.
        runCatching { client.run("tcpip:$TCP_PORT") }
        client.close()
        repeat(TCP_ATTEMPTS) {
            delay(TCP_WAIT_MS)
            tryConnect(TCP_PORT)?.let { return it }
        }
        throw IOException("adb did not come back on port $TCP_PORT")
    }

    /** adbd keeps its port in a system property, as the Shizuku fork reads TCP mode's; some builds let apps read it. */
    @SuppressLint("PrivateApi")
    private fun portFromProperty(): Int? =
        runCatching {
            HiddenApiBypass.addHiddenApiExemptions("Landroid/os/SystemProperties;")
            val get = Class.forName("android.os.SystemProperties").getMethod("getInt", String::class.java, Int::class.javaPrimitiveType)
            (get.invoke(null, "service.adb.tls.port", -1) as Int).takeIf { it > 0 }
        }.getOrNull()

    /** While Shizuku still serves calls, it can read wireless debugging's port without the network. */
    private fun portFromShizuku(): Int? {
        if (connection.source.value != PrivilegedSource.SHIZUKU) return null
        return runCatching {
            val reply = connection.service().runReadOnly(arrayOf("getprop", "service.adb.tls.port"), "", PROP_TIMEOUT_MS)
            ShellReply
                .decode(reply)
                .stdout
                .trim()
                .toIntOrNull()
                ?.takeIf { it > 0 }
        }.getOrNull()
    }

    private fun tryConnect(port: Int): AdbClient? {
        val client = AdbClient(LOCALHOST, port, key)
        return try {
            client.connect()
            client
        } catch (e: IOException) {
            client.close()
            null
        }
    }

    /**
     * The shell command adb runs: grant PocketPilot WRITE_SECURE_SETTINGS (to turn wireless debugging on next
     * time) and NEARBY_WIFI_DEVICES (for mDNS on Android versions that ask for it), stop an older copy, and start
     * [PrivilegedMain] detached (hangups ignored, in its own session where toybox has setsid), so it outlives this
     * adb session.
     */
    private fun command(token: String): String {
        val apk = context.applicationInfo.sourceDir
        require('\'' !in apk) { "Unexpected APK path" }
        val pkg = context.packageName
        val uid = context.applicationInfo.uid
        val main = PrivilegedMain::class.java.name
        return "pm grant $pkg ${Manifest.permission.WRITE_SECURE_SETTINGS} >/dev/null 2>&1; " +
            "pm grant $pkg ${Manifest.permission.NEARBY_WIFI_DEVICES} >/dev/null 2>&1; " +
            "pm grant $pkg $ACCESS_LOCAL_NETWORK >/dev/null 2>&1; " +
            "for p in \$(pidof $PROCESS_NAME); do kill \$p; done; " +
            "trap '' HUP; S=; command -v setsid >/dev/null 2>&1 && S=setsid; " +
            "(CLASSPATH='$apk' \$S app_process /system/bin --nice-name=$PROCESS_NAME $main " +
            "--package=$pkg --uid=$uid --token=$token --apk='$apk' </dev/null >/dev/null 2>&1 &); " +
            "echo $STARTED_MARK"
    }

    companion object {
        const val TCP_PORT = 5555
        const val PROCESS_NAME = "pocketpilot_privileged"
        private const val PREFS = "pocketpilot_privileged_starter"
        private const val KEY_PAIRED = "paired"
        private const val KEY_TCP = "tcp_mode"
        private const val LOCALHOST = "127.0.0.1"
        private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
        private const val ADB_ALLOWED_CONNECTION_TIME = "adb_allowed_connection_time"
        private const val STARTED_MARK = "pocketpilot-started"
        private const val PROP_TIMEOUT_MS = 5_000L
        private const val AUTHORIZE_WAIT_MS = 2_000L
        private const val UNLOCK_DELAY_MS = 1_500L
        private const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

        /**
         * The run-time permissions mDNS discovery and the pairing connection need, as the Shizuku fork asks for
         * them: local network access from Android 17, Nearby devices before that (from Android 13).
         */
        fun localNetworkPermissions(): List<String> =
            when {
                Build.VERSION.SDK_INT >= ANDROID_17 -> listOf(ACCESS_LOCAL_NETWORK)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
                else -> emptyList()
            }

        private const val ANDROID_17 = 37
        private const val DISCOVERY_TIMEOUT_MS = 20_000L
        private const val BINDER_TIMEOUT_MS = 20_000L
        private const val FIRST_START_DELAY_MS = 5_000L
        private const val RESTART_DELAY_MS = 2_000L
        private const val WIFI_DELAY_MS = 3_000L
        private const val TCP_ATTEMPTS = 10
        private const val TCP_WAIT_MS = 1_000L
        private const val MAX_OUTPUT = 2_000
        private val RETRY_DELAYS_MS = longArrayOf(30_000L, 120_000L, 600_000L, 1_800_000L)
    }
}

/** What the owner typed into the pairing notification: the six-digit code, and the port when they gave it. */
internal object PairingInput {
    private const val CODE_LENGTH = 6
    private val PORTS = 1024..65535

    fun parse(input: String): Pair<String, Int?> {
        val numbers = Regex("\\d+").findAll(input).map { it.value }.toList()
        val code = numbers.firstOrNull { it.length == CODE_LENGTH }
        require(code != null) { "The pairing code has $CODE_LENGTH digits" }
        // An address's parts have at most three digits, so the port is the other number of four or five.
        val port =
            numbers
                .filter { it != code && it.length in 4..5 }
                .mapNotNull(String::toIntOrNull)
                .firstOrNull { it in PORTS }
        return code to port
    }
}
