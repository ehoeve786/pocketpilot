package app.pocketpilot.automation

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import app.pocketpilot.core.automation.Place

/**
 * Where [PlaceWatcher] sets its "Arrive at or leave a place" alerts. Each flavor binds its own in its FlavorModule:
 * the oss build uses the platform's proximity alerts, the pro and play builds Google's geofencing, which uses far
 * less battery because it shares the phone's location requests instead of making its own.
 */
interface PlaceAlerts {
    /**
     * Sets an alert around [place], named [key], that sends [intent] on entering or leaving it, replacing one set
     * before with the same [intent]. Throws [SecurityException] without the location permission.
     */
    fun add(
        key: String,
        place: Place,
        intent: PendingIntent,
    )

    /** Removes the alert named [key] that sends [intent]; nothing happens when there is none. */
    fun remove(
        key: String,
        intent: PendingIntent,
    )

    /** True when [intent] reports entering a place, false when it reports leaving one, null when it reports neither. */
    fun entering(intent: Intent): Boolean?
}

/** The platform's proximity alerts, which need no Google services. */
class PlatformPlaceAlerts(
    context: Context,
) : PlaceAlerts {
    private val locations = context.getSystemService(LocationManager::class.java)

    // The caller checks the permission and catches the SecurityException a revoked one throws.
    @SuppressLint("MissingPermission")
    override fun add(
        key: String,
        place: Place,
        intent: PendingIntent,
    ) = locations.addProximityAlert(place.latitude, place.longitude, place.radiusMeters.toFloat(), NO_EXPIRY, intent)

    override fun remove(
        key: String,
        intent: PendingIntent,
    ) {
        try {
            locations.removeProximityAlert(intent)
        } catch (e: SecurityException) {
            // The permission was taken back; Android dropped the alert with it.
        }
    }

    override fun entering(intent: Intent): Boolean? =
        if (intent.hasExtra(LocationManager.KEY_PROXIMITY_ENTERING)) {
            intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false)
        } else {
            null
        }

    private companion object {
        const val NO_EXPIRY = -1L
    }
}
