package app.pocketpilot.capability.api.home

import app.pocketpilot.capability.api.CapabilityBackend

/** One Home Assistant entity, such as `light.kitchen`, with its friendly name and current state. */
data class HomeEntity(
    val id: String,
    val name: String,
    val state: String,
    /** Attributes as Home Assistant reports them, each value as JSON text. */
    val attributes: Map<String, String> = emptyMap(),
) {
    val domain: String get() = id.substringBefore('.')

    /** Tells a garage door or gate apart from a blind, which share the `cover` domain. */
    val deviceClass: String? get() = attributes["device_class"]?.trim('"')
}

/** The owner's Home Assistant, as the `home.*` tools see it. Available once its address and token are set. */
interface HomeControl : CapabilityBackend {
    /** Every entity with its state. */
    suspend fun entities(): List<HomeEntity>

    /** One entity, or null when Home Assistant has none by that id. */
    suspend fun entity(id: String): HomeEntity?

    /**
     * Calls the action [service] ("light.turn_on") on [entity] with [dataJson], a JSON object of extra fields
     * such as `{"brightness_pct": 40}`. Actions that run commands on the host or restart Home Assistant are refused.
     * Returns what Home Assistant answered.
     */
    suspend fun call(
        service: String,
        entity: String,
        dataJson: String,
    ): String
}

/** Which Home Assistant actions open the home to people or run anything, and so need the owner every time. */
object HomeSafety {
    /** Entities whose every action is confirmed: locks, alarm panels, and scripts and automations, which can do anything. */
    val CONFIRMED_DOMAINS = setOf("lock", "alarm_control_panel", "script", "automation", "valve")

    /** Covers that let someone in; blinds, shades and curtains share the `cover` domain and are not confirmed. */
    val CONFIRMED_COVERS = setOf("garage", "gate", "door")

    /**
     * True when calling [service] on [entity] must be confirmed by the owner. A service in a confirmed domain counts
     * even on another entity (`lock.unlock`), and so does any service on a confirmed entity (`homeassistant.toggle`
     * on a lock). With no entity, the service's own domain decides.
     */
    fun needsConfirmation(
        service: String,
        entity: HomeEntity?,
    ): Boolean {
        val serviceDomain = service.substringBefore('.').trim().lowercase()
        if (serviceDomain in CONFIRMED_DOMAINS) return true
        if (serviceDomain == "cover" && entity == null) return true
        entity ?: return false
        return entity.domain in CONFIRMED_DOMAINS ||
            (entity.domain == "cover" && entity.deviceClass?.lowercase() in CONFIRMED_COVERS)
    }
}
