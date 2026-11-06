package app.pocketpilot.automation

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import app.pocketpilot.core.automation.Place
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

/**
 * Place alerts through Google's geofencing, shared by the pro and play builds (`src/gms`). It rides on the location
 * requests the phone already makes, where the platform's proximity alerts make their own, so it costs far less battery.
 * Phones without Play services get the platform's alerts instead.
 */
class GmsPlaceAlerts(
    private val context: Context,
) : PlaceAlerts {
    private val platform = PlatformPlaceAlerts(context)
    private val client by lazy { LocationServices.getGeofencingClient(context) }

    private fun playServices(): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    // The caller checks the permission and catches the SecurityException a revoked one throws.
    @SuppressLint("MissingPermission")
    override fun add(
        key: String,
        place: Place,
        intent: PendingIntent,
    ) {
        if (!playServices()) return platform.add(key, place, intent)
        // A platform alert left from before Play services was there would report the same place twice.
        platform.remove(key, intent)
        val fence =
            Geofence
                .Builder()
                .setRequestId(key)
                .setCircularRegion(place.latitude, place.longitude, place.radiusMeters.toFloat())
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                // Letting Play services report a little late saves the most battery; a place trigger is not urgent.
                .setNotificationResponsiveness(RESPONSIVENESS_MS)
                .build()
        // No initial trigger: a rule saved, or set again after a reboot, while the phone is already there has not arrived.
        val request =
            GeofencingRequest
                .Builder()
                .setInitialTrigger(0)
                .addGeofence(fence)
                .build()
        client
            .addGeofences(request, intent)
            .addOnFailureListener { Log.w(TAG, "A place alert could not be set: ${it.javaClass.simpleName}") }
    }

    override fun remove(
        key: String,
        intent: PendingIntent,
    ) {
        platform.remove(key, intent)
        if (playServices()) client.removeGeofences(listOf(key))
    }

    override fun entering(intent: Intent): Boolean? {
        platform.entering(intent)?.let { return it }
        val event = GeofencingEvent.fromIntent(intent) ?: return null
        if (event.hasError()) {
            // GEOFENCE_NOT_AVAILABLE: location was turned off and every fence went with it; the next rule change sets them again.
            Log.w(TAG, "Place alert error ${event.errorCode}")
            return null
        }
        return when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> true
            Geofence.GEOFENCE_TRANSITION_EXIT -> false
            else -> null
        }
    }

    private companion object {
        const val TAG = "PocketPilot"
        const val RESPONSIVENESS_MS = 2 * 60_000
    }
}
