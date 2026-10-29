package app.pocketpilot.core.automation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenAddressTest {
    private val dasher =
        mapOf(
            "com.doordash.driverapp:id/address_line_1" to "666 1300- Burrard St",
            "com.doordash.driverapp:id/address_line_2" to "Vancouver, BC V6C 2X8, Canada",
        )

    @Test
    fun `a dasher screen gives the street and city, with the unit taken out of the street`() {
        assertEquals("666 Burrard St, Vancouver, BC V6C 2X8, Canada", ScreenAddress.find { dasher[it] })
    }

    @Test
    fun `a street without a unit is left as it is`() {
        assertEquals("616 Davie St", ScreenAddress.clean("616 Davie St"))
    }

    @Test
    fun `an uber screen gives its address, and a business name in the point of interest is not added to it`() {
        val uber = mapOf("com.ubercab.driver:id/ub__nav_address_view_address_text" to "1668 Davie St, Vancouver")
        assertEquals("1668 Davie St, Vancouver", ScreenAddress.find { uber[it] })

        val numbered = uber + ("com.ubercab.driver:id/ub__nav_address_view_poi_text" to "7-Eleven (1668 Davie Street)")
        assertEquals("1668 Davie St, Vancouver", ScreenAddress.find { numbered[it] })
    }

    @Test
    fun `a dasher city line that starts with the unit gives the city without it`() {
        val withUnit =
            mapOf(
                "com.doordash.driverapp:id/address_line_1" to "666 1300- Burrard St",
                "com.doordash.driverapp:id/address_line_2" to "# 1300, Vancouver, BC V6C 2X8, Canada",
            )
        assertEquals("666 Burrard St, Vancouver, BC V6C 2X8, Canada", ScreenAddress.find { withUnit[it] })
    }

    @Test
    fun `an address range is not taken for a unit`() {
        assertEquals("100 12-14 Street", ScreenAddress.clean("100 12-14 Street"))
    }

    @Test
    fun `a value with a placeholder left in it counts as missing`() {
        val unresolved = mapOf("com.doordash.driverapp:id/address_line_1" to "{screen_read.com.doordash.driverapp:id/address_line_1}")
        assertNull(ScreenAddress.find { unresolved[it] })
    }

    @Test
    fun `a screen with no known address gives nothing, so the AI can answer`() {
        assertNull(ScreenAddress.find { null })
        assertTrue(ScreenAddress.RECIPES.isNotEmpty())
    }

    @Test
    fun `a shared rule that reads an unset variable imports with a warning and no problem`() {
        val rule =
            Rule(
                id = "r1",
                name = "Send",
                triggers = listOf(TriggerSpec("manual")),
                actions = listOf(ActionStep("notify.post", mapOf("title" to "{missing}"))),
            )

        val check = checkSharedRule(rule.shareFileJson(), { "new" }, sideload = true, globals = emptySet())!!

        assertEquals(emptyList(), check.problems)
        assertEquals(1, check.warnings.size)
    }

    @Test
    fun `a shared rule with an unknown action is a problem`() {
        val rule =
            Rule(
                id = "r1",
                name = "Send",
                triggers = listOf(TriggerSpec("manual")),
                actions = listOf(ActionStep("no.such.action")),
            )

        val check = checkSharedRule(rule.shareFileJson(), { "new" }, sideload = true, globals = emptySet())!!

        assertEquals(listOf("Unknown action no.such.action"), check.problems)
    }

    @Test
    fun `a file that is not a rule has no check`() {
        assertNull(checkSharedRule("not json", { "new" }, sideload = true, globals = emptySet()))
    }
}
