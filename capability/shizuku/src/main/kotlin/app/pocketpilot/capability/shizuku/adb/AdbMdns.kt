package app.pocketpilot.capability.shizuku.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Executor

/** Finds this phone's wireless debugging ports, which adbd announces over mDNS and changes on every restart. */
class AdbMdns(
    context: Context,
) {
    private val nsd = context.getSystemService(NsdManager::class.java)

    /** Why the last search found nothing, in words for the owner, or null. */
    @Volatile
    var lastProblem: String? = null
        private set

    /** The port of [type] that this phone itself announces, or null after [timeoutMs]. */
    suspend fun find(
        type: String,
        timeoutMs: Long,
    ): Int? {
        lastProblem = null
        val port = withTimeoutOrNull(timeoutMs) { ports(type).firstOrNull() }
        if (port == null && lastProblem == null) lastProblem = "Wireless debugging was not found on the local network"
        return port
    }

    /**
     * Ports of [type] that belong to this phone, as they are resolved. Other phones on the same Wi-Fi announce
     * their own; a connect port counts when it is on one of this phone's addresses or answers on 127.0.0.1, where
     * adbd listens too (the address check alone misses some IPv6 and scoped addresses). A pairing port is only
     * announced while a pairing dialog is open, and a test connection could end that pairing, so the first one
     * found is taken.
     */
    fun ports(type: String): Flow<Int> =
        callbackFlow {
            // Resolver callbacks still being registered when the search ends are unregistered then.
            val pending = mutableListOf<NsdManager.ServiceInfoCallback>()
            val listener =
                object : NsdManager.DiscoveryListener {
                    override fun onServiceFound(info: NsdServiceInfo) {
                        resolve(info, pending) { host, port -> trySend(host to port) }
                    }

                    override fun onStartDiscoveryFailed(
                        serviceType: String,
                        errorCode: Int,
                    ) {
                        lastProblem = "Searching the local network failed (error $errorCode); allow Nearby devices for PocketPilot"
                        close()
                    }

                    override fun onDiscoveryStarted(serviceType: String) = Unit

                    override fun onDiscoveryStopped(serviceType: String) = Unit

                    override fun onStopDiscoveryFailed(
                        serviceType: String,
                        errorCode: Int,
                    ) = Unit

                    override fun onServiceLost(info: NsdServiceInfo) = Unit
                }
            try {
                nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (e: RuntimeException) {
                lastProblem = "Searching the local network was refused: ${e.message}"
                close()
            }
            awaitClose {
                runCatching { nsd.stopServiceDiscovery(listener) }
                synchronized(pending) { pending.toList().also { pending.clear() } }
                    .forEach { runCatching { nsd.unregisterServiceInfoCallback(it) } }
            }
        }.filter { (host, port) -> type == TLS_PAIRING || isOwnAddress(host) || answersLocally(port) }
            .map { it.second }
            .flowOn(Dispatchers.IO)

    /** Resolves [info] to a host and port; Android 14 and later replaced resolveService with a callback. */
    private fun resolve(
        info: NsdServiceInfo,
        pending: MutableList<NsdManager.ServiceInfoCallback>,
        onResolved: (String?, Int) -> Unit,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val callback =
                object : NsdManager.ServiceInfoCallback {
                    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = Unit

                    override fun onServiceUpdated(updated: NsdServiceInfo) {
                        val host = updated.hostAddresses.firstOrNull()?.hostAddress
                        if (updated.port > 0) onResolved(host, updated.port)
                        synchronized(pending) { pending.remove(this) }
                        runCatching { nsd.unregisterServiceInfoCallback(this) }
                    }

                    override fun onServiceLost() = Unit

                    override fun onServiceInfoCallbackUnregistered() = Unit
                }
            synchronized(pending) { pending.add(callback) }
            runCatching { nsd.registerServiceInfoCallback(info, DIRECT, callback) }
        } else {
            @Suppress("DEPRECATION")
            nsd.resolveService(
                info,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(
                        info: NsdServiceInfo,
                        errorCode: Int,
                    ) = Unit

                    @Suppress("DEPRECATION")
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        onResolved(info.host?.hostAddress, info.port)
                    }
                },
            )
        }
    }

    private fun isOwnAddress(host: String?): Boolean =
        host != null &&
            runCatching {
                NetworkInterface
                    .getNetworkInterfaces()
                    .asSequence()
                    .flatMap { it.inetAddresses.asSequence() }
                    .any { it.hostAddress == host }
            }.getOrDefault(false)

    private fun answersLocally(port: Int): Boolean =
        try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), LOCAL_CHECK_MS) }
            true
        } catch (e: IOException) {
            false
        }

    companion object {
        const val TLS_CONNECT = "_adb-tls-connect._tcp"
        const val TLS_PAIRING = "_adb-tls-pairing._tcp"
        private const val LOCAL_CHECK_MS = 500

        /**
         * Runs NsdManager's callbacks on its own thread. They only hand a port on, and an executor that is shut down
         * when a search ends would reject a late callback and crash the app (RejectedExecutionException).
         */
        private val DIRECT = Executor { it.run() }
    }
}
