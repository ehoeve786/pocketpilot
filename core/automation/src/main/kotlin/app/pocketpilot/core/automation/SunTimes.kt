package app.pocketpilot.core.automation

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** Sunrise and sunset for a place, from the NOAA approximation; accurate to a minute or two. */
internal object SunTimes {
    /** Sunrise and sunset on [date] at [latitude], [longitude] in [zone], or null on a day with no sunrise or no sunset. */
    fun of(
        date: LocalDate,
        latitude: Double,
        longitude: Double,
        zone: ZoneId,
    ): Pair<ZonedDateTime, ZonedDateTime>? {
        val rise = event(date, latitude, longitude, rising = true) ?: return null
        val set = event(date, latitude, longitude, rising = false) ?: return null
        return onDate(date, rise, zone) to onDate(date, set, zone)
    }

    /** [hourUtc] as a time on the local [date]: the UTC day is moved by one when the zone puts it on the day before or after. */
    private fun onDate(
        date: LocalDate,
        hourUtc: Double,
        zone: ZoneId,
    ): ZonedDateTime {
        val time = date.atStartOfDay(ZoneId.of("UTC")).plusSeconds((hourUtc * 3600).toLong()).withZoneSameInstant(zone)
        return when {
            time.toLocalDate() < date -> time.plusDays(1)
            time.toLocalDate() > date -> time.minusDays(1)
            else -> time
        }
    }

    /** The hour, in UTC, of sunrise or sunset on [date]. */
    private fun event(
        date: LocalDate,
        latitude: Double,
        longitude: Double,
        rising: Boolean,
    ): Double? {
        val day = date.dayOfYear
        val lngHour = longitude / 15
        val t = day + ((if (rising) 6.0 else 18.0) - lngHour) / 24
        val meanAnomaly = 0.9856 * t - 3.289
        val trueLongitude =
            normalize(meanAnomaly + 1.916 * sin(rad(meanAnomaly)) + 0.020 * sin(rad(2 * meanAnomaly)) + 282.634, 360.0)
        var ascension = normalize(deg(kotlin.math.atan(0.91764 * kotlin.math.tan(rad(trueLongitude)))), 360.0)
        ascension += floor(trueLongitude / 90) * 90 - floor(ascension / 90) * 90
        ascension /= 15
        val sinDec = 0.39782 * sin(rad(trueLongitude))
        val cosDec = cos(asin(sinDec))
        val cosH = (cos(rad(ZENITH)) - sinDec * sin(rad(latitude))) / (cosDec * cos(rad(latitude)))
        if (cosH > 1 || cosH < -1) return null
        val h = (if (rising) 360 - deg(acos(cosH)) else deg(acos(cosH))) / 15
        val local = h + ascension - 0.06571 * t - 6.622
        return normalize(local - lngHour, 24.0)
    }

    private const val ZENITH = 90.833

    private fun rad(degrees: Double) = degrees * PI / 180

    private fun deg(radians: Double) = radians * 180 / PI

    private fun normalize(
        value: Double,
        range: Double,
    ): Double = ((value % range) + range) % range
}
