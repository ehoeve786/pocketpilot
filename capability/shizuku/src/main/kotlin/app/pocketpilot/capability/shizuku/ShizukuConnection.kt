package app.pocketpilot.capability.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import app.pocketpilot.core.model.ToolErrorCode
import app.pocketpilot.core.model.ToolException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import rikka.shizuku.Shizuku

/** Where PocketPilot stands with Shizuku, shown in Doctor. */
enum class ShizukuState {
    /** Shizuku is not installed or not started. */
    NOT_RUNNING,

    /** Shizuku is older than version 11, which has no user services. */
    UNSUPPORTED,

    /** Shizuku runs, but the owner has not allowed PocketPilot yet. */
    PERMISSION_NEEDED,

    /** Starting the privileged service. */
    CONNECTING,
    CONNECTED,
}

/** Which process serves [ShizukuConnection.service]. */
enum class PrivilegedSource {
    /** PocketPilot's own process, started over wireless debugging by PrivilegedStarter. */
    BUILT_IN,

    /** The user service Shizuku starts. */
    SHIZUKU,
}

/**
 * Keeps a [PrivilegedService] at hand. PocketPilot's own privileged process (PrivilegedMain) comes first when it
 * has sent its binder; otherwise this follows Shizuku's binder and permission and keeps the service bound through
 * Shizuku while both are there. Shizuku restarts its service when [PrivilegedService.VERSION] changes; an own
 * process of another version is stopped, and PrivilegedStarter starts a new one.
 */
class ShizukuConnection(
    private val context: Context,
) {
    private val mutableState = MutableStateFlow(ShizukuState.NOT_RUNNING)
    val state: StateFlow<ShizukuState> = mutableState.asStateFlow()

    private val mutableSource = MutableStateFlow<PrivilegedSource?>(null)

    /** Which process serves calls now, or null when none does. */
    val source: StateFlow<PrivilegedSource?> = mutableSource.asStateFlow()

    /** Shizuku's own state, which [state] shows unless the built-in process is connected. */
    private var shizukuState = ShizukuState.NOT_RUNNING

    /** The built-in process's service; set and cleared on the main thread. */
    @Volatile
    private var direct: IPocketPilotPrivileged? = null

    @Volatile
    private var service: IPocketPilotPrivileged? = null

    /** True while a bind is outstanding, so repeated refreshes do not start more of them. */
    private var binding = false

    /** Disconnects in a row, which lengthen the wait before the next bind. */
    private var disconnects = 0

    private val handler = Handler(Looper.getMainLooper())

    private val connectTimeout =
        Runnable {
            if (binding && service == null) {
                binding = false
                refresh()
            }
        }

    private val args =
        Shizuku
            .UserServiceArgs(ComponentName(context.packageName, PrivilegedService::class.java.name))
            .daemon(false)
            .processNameSuffix("privileged")
            .tag("pocketpilot-privileged")
            .version(PrivilegedService.VERSION)

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName,
                binder: IBinder?,
            ) {
                if (binder == null || !binder.pingBinder()) return
                handler.removeCallbacks(connectTimeout)
                service = IPocketPilotPrivileged.Stub.asInterface(binder)
                binding = false
                disconnects = 0
                shizukuState = ShizukuState.CONNECTED
                publish()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                service = null
                binding = false
                disconnects++
                shizukuState = ShizukuState.CONNECTING
                publish()
                // A service that keeps dying is retried less often, so it cannot loop bind after bind.
                handler.postDelayed({ refresh() }, minOf(MAX_RETRY_MS, BASE_RETRY_MS shl minOf(disconnects - 1, 6)))
            }
        }

    private var started = false

    /** Starts following Shizuku. Call once, from the main thread. */
    fun start() {
        if (started) return
        started = true
        Shizuku.addBinderReceivedListenerSticky { refresh() }
        Shizuku.addBinderDeadListener {
            service = null
            shizukuState = ShizukuState.NOT_RUNNING
            publish()
        }
        Shizuku.addRequestPermissionResultListener { _, _ -> refresh() }
        PrivilegedBinderHub.onReceived = { received -> handler.post { attach(received) } }
        PrivilegedBinderHub.latest.value?.let(::attach)
    }

    /** Takes the binder of PocketPilot's own privileged process, unless it runs another contract version. */
    private fun attach(received: ReceivedBinder) {
        val binder = received.binder
        if (direct?.asBinder() === binder || !binder.pingBinder()) return
        val candidate = IPocketPilotPrivileged.Stub.asInterface(binder)
        if (received.version != PrivilegedService.VERSION) {
            // Left over from before an update; PrivilegedStarter starts the current one.
            runCatching { candidate.destroy() }
            return
        }
        val linked =
            runCatching {
                binder.linkToDeath({ handler.post { detach(binder) } }, 0)
            }.isSuccess
        if (!linked) return
        direct = candidate
        publish()
    }

    private fun detach(binder: IBinder) {
        if (direct?.asBinder() !== binder) return
        direct = null
        publish()
        refresh()
    }

    private fun publish() {
        mutableState.value = if (direct != null) ShizukuState.CONNECTED else shizukuState
        mutableSource.value =
            when {
                direct != null -> PrivilegedSource.BUILT_IN
                service != null -> PrivilegedSource.SHIZUKU
                else -> null
            }
    }

    /** Shows Shizuku's permission dialog. */
    fun requestPermission() {
        if (Shizuku.pingBinder() && !Shizuku.isPreV11()) Shizuku.requestPermission(PERMISSION_REQUEST)
    }

    /** The bound privileged service, or CAPABILITY_UNAVAILABLE saying what to do. */
    fun service(): IPocketPilotPrivileged =
        direct ?: service ?: throw ToolException(
            ToolErrorCode.CAPABILITY_UNAVAILABLE,
            "Shizuku is not connected (${state.value.name.lowercase().replace('_', ' ')})",
            "Ask the phone's owner to start Shizuku and allow PocketPilot, or to turn on PocketPilot in Accessibility settings",
        )

    /** True while the privileged service is bound. */
    fun connectedFlow() = state.map { it == ShizukuState.CONNECTED }

    private fun refresh() {
        shizukuState =
            when {
                !Shizuku.pingBinder() -> {
                    binding = false
                    ShizukuState.NOT_RUNNING
                }

                Shizuku.isPreV11() -> {
                    ShizukuState.UNSUPPORTED
                }

                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> {
                    ShizukuState.PERMISSION_NEEDED
                }

                service != null -> {
                    ShizukuState.CONNECTED
                }

                else -> {
                    // The built-in process serves calls, so Shizuku's copy is not started as well.
                    if (!binding && direct == null) {
                        binding = true
                        Shizuku.bindUserService(args, connection)
                        // A bind that never calls back would leave the state at CONNECTING for good, so give up on it.
                        handler.removeCallbacks(connectTimeout)
                        handler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
                    }
                    ShizukuState.CONNECTING
                }
            }
        publish()
    }

    private companion object {
        const val PERMISSION_REQUEST = 4_201
        const val BASE_RETRY_MS = 1_000L
        const val MAX_RETRY_MS = 60_000L
        const val CONNECT_TIMEOUT_MS = 15_000L
    }
}
