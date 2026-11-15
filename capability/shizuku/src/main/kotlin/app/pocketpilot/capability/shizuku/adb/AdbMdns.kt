package app.pocketpilot.capability.shizuku.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import java.net.NetworkInterface

/** Finds this phone's wireless debugging ports, which adbd announces over mDNS and changes on every restart. */
class AdbMdns(
    context: Context,
) {
    private val nsd = context.getSystemService(NsdManager::class.java)

    /** The port of [type] that this phone itself announces, or null after [timeoutMs]. */
    suspend fun find(
        type: String,
        timeoutMs: Long,
    ): Int? = withTimeoutOrNull(timeoutMs) { ports(type).firstOrNull() }

    /** Ports of [type] announced from one of this phone's own addresses, as they are resolved. */
    @Suppress("DEPRECATION")
    fun ports(type: String): Flow<Int> =
        callbackFlow {
            val listener =
                object : NsdManager.DiscoveryListener {
                    override fun onServiceFound(info: NsdServiceInfo) {
                        nsd.resolveService(
                            info,
                            object : NsdManager.ResolveListener {
                                override fun onResolveFailed(
                                    info: NsdServiceInfo,
                                    errorCode: Int,
                                ) = Unit

                                override fun onServiceResolved(info: NsdServiceInfo) {
                                    val host = info.host?.hostAddress ?: return
                                    if (isOwnAddress(host)) trySend(info.port)
                                }
                            },
                        )
                    }

                    override fun onStartDiscoveryFailed(
                        serviceType: String,
                        errorCode: Int,
                    ) {
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
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
            awaitClose { runCatching { nsd.stopServiceDiscovery(listener) } }
        }

    /** Other phones on the same Wi-Fi announce their own ports; only this phone's count. */
    private fun isOwnAddress(host: String): Boolean =
        runCatching {
            NetworkInterface
                .getNetworkInterfaces()
                .asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .any { it.hostAddress == host }
        }.getOrDefault(false)

    companion object {
        const val TLS_CONNECT = "_adb-tls-connect._tcp"
        const val TLS_PAIRING = "_adb-tls-pairing._tcp"
    }
}
