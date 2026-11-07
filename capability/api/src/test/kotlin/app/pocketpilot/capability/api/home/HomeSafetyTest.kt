package app.pocketpilot.capability.api.home

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeSafetyTest {
    private fun entity(
        id: String,
        deviceClass: String? = null,
    ) = HomeEntity(id, id, "off", deviceClass?.let { mapOf("device_class" to "\"$it\"") } ?: emptyMap())

    @Test
    fun lightsSwitchesScenesAndBlindsRunWithoutAsking() {
        assertFalse(HomeSafety.needsConfirmation("light.turn_on", entity("light.living_room")))
        assertFalse(HomeSafety.needsConfirmation("switch.toggle", entity("switch.fan")))
        assertFalse(HomeSafety.needsConfirmation("scene.turn_on", entity("scene.movie")))
        assertFalse(HomeSafety.needsConfirmation("cover.open_cover", entity("cover.bedroom_blind", "blind")))
        assertFalse(HomeSafety.needsConfirmation("cover.open_cover", entity("cover.kitchen_shade")))
    }

    @Test
    fun locksAlarmsGaragesScriptsAndAutomationsAsk() {
        assertTrue(HomeSafety.needsConfirmation("lock.unlock", entity("lock.front_door")))
        assertTrue(HomeSafety.needsConfirmation("alarm_control_panel.alarm_disarm", entity("alarm_control_panel.home")))
        assertTrue(HomeSafety.needsConfirmation("cover.open_cover", entity("cover.garage", "garage")))
        assertTrue(HomeSafety.needsConfirmation("cover.open_cover", entity("cover.side", "GATE")))
        assertTrue(HomeSafety.needsConfirmation("script.turn_on", entity("script.leave_home")))
        assertTrue(HomeSafety.needsConfirmation("automation.trigger", entity("automation.night")))
    }

    @Test
    fun aGenericActionOnALockStillAsks() {
        assertTrue(HomeSafety.needsConfirmation("homeassistant.toggle", entity("lock.front_door")))
        assertTrue(HomeSafety.needsConfirmation("Lock.Unlock", entity("light.mislabelled")))
    }

    @Test
    fun aCoverWithNoEntityAsks() {
        assertTrue(HomeSafety.needsConfirmation("cover.open_cover", null))
        assertFalse(HomeSafety.needsConfirmation("light.turn_on", null))
    }
}
