package app.pocketpilot.capability.api

/** Where the phone is, as Android last knew it or just found it. */
data class DeviceLocation(
    val latitude: Double,
    val longitude: Double,
    /** Radius of 68% confidence, in metres, when the provider gave one. */
    val accuracyMeters: Float?,
    /** How old the fix is. */
    val ageMillis: Long,
    /** The nearest street address, when the phone could look one up. */
    val address: String?,
)

/** The phone's location for `device.location`. */
interface LocationReader {
    /** True when PocketPilot may read the location. */
    val permitted: Boolean

    /** A recent fix with its address, or null when the phone has none, for example with location turned off. */
    suspend fun read(): DeviceLocation?
}
