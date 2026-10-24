package app.pocketpilot.core.automation

import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoreConstraintsTest {
    private class Scope(
        override val now: ZonedDateTime,
        val values: Map<String, String> = emptyMap(),
        override val triggerType: String? = null,
        val running: Set<String> = emptySet(),
        val stopwatches: Map<String, Long> = emptyMap(),
    ) : CheckScope {
        override suspend fun state(key: String): String? = values[key]

        override suspend fun notifications(): List<NotificationInfo> = emptyList()

        override fun variable(name: String): String? = null

        override val rule: Rule = Rule("r1", "Test")

        override fun lastRunAt(ruleId: String): Long? = null

        override fun runsToday(ruleId: String): Int = 0

        override fun ruleEnabled(ruleId: String): Boolean? = null

        override fun ruleRunning(ruleId: String): Boolean = ruleId in running

        override fun stopwatchMillis(name: String): Long? = stopwatches[name]
    }

    private val vancouver = ZoneId.of("America/Vancouver")

    private fun at(
        month: Int,
        day: Int,
        hour: Int,
    ) = ZonedDateTime.of(2026, month, day, hour, 0, 0, 0, vancouver)

    private suspend fun holds(
        type: String,
        params: Map<String, String>,
        scope: Scope,
    ): Boolean {
        val def = assertNotNull(AutomationCatalog.constraint(type), type)
        return def.check(scope, RuleValidator.withDefaults(def.fields, params))
    }

    @Test
    fun `day of the month and month ranges wrap around`() =
        runTest {
            assertTrue(holds("time.month_day", mapOf("from" to "28", "to" to "3"), Scope(at(10, 2, 9))))
            assertFalse(holds("time.month_day", mapOf("from" to "28", "to" to "3"), Scope(at(10, 15, 9))))
            // 31 means the last day, so a 30-day month still matches on the 30th.
            assertTrue(holds("time.month_day", mapOf("from" to "31", "to" to "31"), Scope(at(9, 30, 9))))
            assertTrue(holds("time.month", mapOf("from" to "dec", "to" to "feb"), Scope(at(1, 10, 9))))
            assertFalse(holds("time.month", mapOf("from" to "dec", "to" to "feb"), Scope(at(10, 10, 9))))
        }

    @Test
    fun `sunrise and sunset fall at the right local times`() {
        val (rise, set) = assertNotNull(SunTimes.of(LocalDate.of(2026, 10, 12), 49.2827, -123.1207, vancouver))
        assertEquals(LocalDate.of(2026, 10, 12), rise.toLocalDate())
        assertEquals(LocalDate.of(2026, 10, 12), set.toLocalDate())
        assertTrue(rise.hour == 7, "sunrise $rise")
        assertTrue(set.hour == 18, "sunset $set")
        val tokyo = ZoneId.of("Asia/Tokyo")
        val (tokyoRise, tokyoSet) = assertNotNull(SunTimes.of(LocalDate.of(2026, 10, 12), 35.68, 139.76, tokyo))
        assertEquals(5, tokyoRise.hour)
        assertEquals(17, tokyoSet.hour)
        // No sunset in the Arctic summer.
        assertNull(SunTimes.of(LocalDate.of(2026, 6, 21), 78.2, 15.6, ZoneId.of("Arctic/Longyearbyen")))
    }

    @Test
    fun `day and night follow the sun where the phone is`() =
        runTest {
            val here = mapOf(StateKeys.LOCATION to "49.2827,-123.1207")
            assertTrue(holds("time.sun", mapOf("state" to "day"), Scope(at(10, 12, 13), here)))
            assertTrue(holds("time.sun", mapOf("state" to "night"), Scope(at(10, 12, 22), here)))
            assertFalse(holds("time.sun", mapOf("state" to "day"), Scope(at(10, 12, 22), here)))
            assertFalse(holds("time.sun", mapOf("state" to "day"), Scope(at(10, 12, 13))))
        }

    @Test
    fun `ranges, volumes and settings read their own state`() =
        runTest {
            val scope =
                Scope(
                    at(10, 12, 9),
                    mapOf(
                        StateKeys.BATTERY_TEMPERATURE to "38.5",
                        StateKeys.volume("notification") to "40",
                        StateKeys.setting("system", "screen_off_timeout") to "60000",
                        StateKeys.IP_ADDRESSES to "10.1.0.2\n192.168.8.20",
                        StateKeys.UPTIME_MINUTES to "90",
                    ),
                )
            assertTrue(holds("battery.temperature", mapOf("min" to "30", "max" to "40"), scope))
            assertFalse(holds("battery.temperature", mapOf("max" to "35"), scope))
            assertTrue(holds("volume.level", mapOf("stream" to "notification", "min" to "25", "max" to "50"), scope))
            assertFalse(holds("volume.level", mapOf("stream" to "media", "min" to "0"), scope))
            assertTrue(holds("setting.value", mapOf("name" to "screen_off_timeout", "op" to "greater", "value" to "30000"), scope))
            assertTrue(holds("network.ip", mapOf("ip" to "192.168.8."), scope))
            assertFalse(holds("network.ip", mapOf("ip" to "172.16."), scope))
            assertTrue(holds("time.since_boot", mapOf("min" to "60"), scope))
        }

    @Test
    fun `trigger fired, rule running and the stopwatch use the run's own state`() =
        runTest {
            val fromWifi = Scope(at(10, 12, 9), triggerType = "wifi.connected", running = setOf("r2"), stopwatches = mapOf("lap" to 90_000))
            assertTrue(holds("trigger.fired", mapOf("trigger" to "wifi.connected"), fromWifi))
            assertFalse(holds("trigger.fired", mapOf("trigger" to "manual"), fromWifi))
            assertTrue(holds("trigger.fired", mapOf("trigger" to "manual"), Scope(at(10, 12, 9))))
            assertTrue(holds("rule.running", mapOf("rule" to "r2"), fromWifi))
            assertTrue(holds("rule.running", mapOf("rule" to "r3", "state" to "no"), fromWifi))
            assertTrue(holds("stopwatch.time", mapOf("name" to "lap", "op" to "more", "seconds" to "60"), fromWifi))
            assertFalse(holds("stopwatch.time", mapOf("name" to "other", "op" to "more", "seconds" to "1"), fromWifi))
        }

    @Test
    fun `the home screen, apps and cell towers are matched by name`() =
        runTest {
            val scope =
                Scope(
                    at(10, 12, 9),
                    mapOf(
                        StateKeys.HOME_APP to "com.sec.android.app.launcher",
                        StateKeys.FOREGROUND to "com.sec.android.app.launcher",
                        StateKeys.appInstalled("com.ubercab.driver") to "on",
                        StateKeys.CELL_TOWERS to "302-720-1234-5678\n302-720-1234-9999",
                    ),
                )
            assertTrue(holds("launcher.front", emptyMap(), scope))
            assertTrue(holds("app.installed", mapOf("app" to "com.ubercab.driver"), scope))
            assertFalse(holds("app.installed", mapOf("app" to "com.doordash.driverapp"), scope))
            assertTrue(holds("cell.tower", mapOf("towers" to "302-720-1234-9999"), scope))
            assertTrue(holds("cell.tower", mapOf("towers" to "1-2-3-4", "state" to "no"), scope))
            // Unknown towers never count as away, so a phone without location access does not run the rule.
            assertFalse(holds("cell.tower", mapOf("towers" to "1-2-3-4", "state" to "no"), Scope(at(10, 12, 9))))
        }

    @Test
    fun `a stopwatch adds the time since it started to the time it ran before`() {
        assertEquals(5_000, Stopwatch(elapsedMillis = 5_000).millis(100_000))
        assertEquals(15_000, Stopwatch(elapsedMillis = 5_000, startedAtMillis = 90_000).millis(100_000))
    }
}
