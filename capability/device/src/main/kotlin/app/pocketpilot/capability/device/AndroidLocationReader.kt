package app.pocketpilot.capability.device

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import app.pocketpilot.capability.api.DeviceLocation
import app.pocketpilot.capability.api.LocationReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * The newest location any provider has, or a fresh fix when that is older than [FRESH_MS]. The address comes from
 * Android's own geocoder, so no other service sees the location.
 */
class AndroidLocationReader(
    private val context: Context,
) : LocationReader {
    private val locations = context.getSystemService(LocationManager::class.java)

    override val permitted: Boolean
        get() = granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    override suspend fun read(): DeviceLocation? {
        if (!permitted || !locations.isLocationEnabled) return null
        val last = lastKnown()
        val fix = last?.takeIf { age(it) <= FRESH_MS } ?: current() ?: last ?: return null
        return DeviceLocation(
            latitude = fix.latitude,
            longitude = fix.longitude,
            accuracyMeters = fix.accuracy.takeIf { fix.hasAccuracy() },
            ageMillis = age(fix),
            address = address(fix),
        )
    }

    // read() checks the permission first; a permission taken back meanwhile throws, which the tool reports.
    @SuppressLint("MissingPermission")
    private fun lastKnown(): Location? =
        locations
            .getProviders(true)
            .mapNotNull { runCatching { locations.getLastKnownLocation(it) }.getOrNull() }
            .minByOrNull { age(it) }

    @SuppressLint("MissingPermission")
    private suspend fun current(): Location? {
        val provider =
            listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter { Build.VERSION.SDK_INT >= Build.VERSION_CODES.S || it != LocationManager.FUSED_PROVIDER }
                .firstOrNull { runCatching { locations.isProviderEnabled(it) }.getOrDefault(false) }
                ?: return null
        return withTimeoutOrNull(FIX_TIMEOUT_MS) {
            suspendCancellableCoroutine<Location?> { continuation ->
                val cancel = CancellationSignal()
                continuation.invokeOnCancellation { cancel.cancel() }
                locations.getCurrentLocation(provider, cancel, context.mainExecutor) { location ->
                    if (continuation.isActive) continuation.resume(location)
                }
            }
        }
    }

    private suspend fun address(location: Location): String? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.getDefault())
        return withTimeoutOrNull(GEOCODE_TIMEOUT_MS) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine<String?> { continuation ->
                    geocoder.getFromLocation(
                        location.latitude,
                        location.longitude,
                        1,
                        object : Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: MutableList<Address>) {
                                if (continuation.isActive) continuation.resume(addresses.firstOrNull()?.getAddressLine(0))
                            }

                            override fun onError(errorMessage: String?) {
                                if (continuation.isActive) continuation.resume(null)
                            }
                        },
                    )
                }
            } else {
                withContext(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    runCatching { geocoder.getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull()?.getAddressLine(0) }
                        .getOrNull()
                }
            }
        }
    }

    private fun age(location: Location): Long = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / NANOS_PER_MS

    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val FRESH_MS = 2 * 60_000L
        const val FIX_TIMEOUT_MS = 10_000L
        const val GEOCODE_TIMEOUT_MS = 5_000L
        const val NANOS_PER_MS = 1_000_000L
    }
}
