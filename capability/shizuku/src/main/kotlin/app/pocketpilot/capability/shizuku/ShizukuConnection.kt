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

/**
 * Follows Shizuku's binder and permission, and keeps PocketPilot's [PrivilegedService] bound while
 * both are there. Shizuku restarts the service when [PrivilegedService.VERSION] changes.
 */
class ShizukuConnection(
    private val context: Context,
) {
    private val mutableState = MutableStateFlow(ShizukuState.NOT_RUNNING)
    val state: StateFlow<ShizukuState> = mutableState.asStateFlow()

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
                mutableState.value = ShizukuState.CONNECTED
            }

            override fun onServiceDisconnected(name: ComponentName) {
                service = null
                binding = false
                disconnects++
                mutableState.value = ShizukuState.CONNECTING
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
            mutableState.value = ShizukuState.NOT_RUNNING
        }
        Shizuku.addRequestPermissionResultListener { _, _ -> refresh() }
    }

    /** Shows Shizuku's permission dialog. */
    fun requestPermission() {
        if (Shizuku.pingBinder() && !Shizuku.isPreV11()) Shizuku.requestPermission(PERMISSION_REQUEST)
    }

    /** The bound privileged service, or CAPABILITY_UNAVAILABLE saying what to do. */
    fun service(): IPocketPilotPrivileged =
        service ?: throw ToolException(
            ToolErrorCode.CAPABILITY_UNAVAILABLE,
            "Shizuku is not connected (${state.value.name.lowercase().replace('_', ' ')})",
            "Ask the phone's owner to start Shizuku and allow PocketPilot, or to turn on PocketPilot in Accessibility settings",
        )

    /** True while the privileged service is bound. */
    fun connectedFlow() = state.map { it == ShizukuState.CONNECTED }

    private fun refresh() {
        mutableState.value =
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
                    if (!binding) {
                        binding = true
                        Shizuku.bindUserService(args, connection)
                        // A bind that never calls back would leave the state at CONNECTING for good, so give up on it.
                        handler.removeCallbacks(connectTimeout)
                        handler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
                    }
                    ShizukuState.CONNECTING
                }
            }
    }

    private companion object {
        const val PERMISSION_REQUEST = 4_201
        const val BASE_RETRY_MS = 1_000L
        const val MAX_RETRY_MS = 60_000L
        const val CONNECT_TIMEOUT_MS = 15_000L
    }
}
