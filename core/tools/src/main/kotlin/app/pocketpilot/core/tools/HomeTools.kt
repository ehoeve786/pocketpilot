package app.pocketpilot.core.tools

import app.pocketpilot.capability.api.home.HomeControl
import app.pocketpilot.capability.api.home.HomeEntity
import app.pocketpilot.capability.api.home.HomeSafety
import app.pocketpilot.core.model.Availability
import app.pocketpilot.core.model.CapabilityId
import app.pocketpilot.core.model.Phase
import app.pocketpilot.core.model.RiskTier
import app.pocketpilot.core.model.Scope
import app.pocketpilot.core.model.Session
import app.pocketpilot.core.model.ToolAnnotations
import app.pocketpilot.core.model.ToolCall
import app.pocketpilot.core.model.ToolErrorCode
import app.pocketpilot.core.model.ToolException
import app.pocketpilot.core.model.ToolResult
import app.pocketpilot.core.model.ToolSpec
import app.pocketpilot.core.orchestrator.ToolHandler
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/**
 * Runs [block] against the owner's Home Assistant, turning its failures into tool errors whose message says why.
 * Before Home Assistant is set up, every home.* call fails with a hint for the owner instead.
 */
private suspend fun <T> HomeControl.reach(block: suspend () -> T): T {
    if (!available.value) {
        throw ToolException(
            ToolErrorCode.CAPABILITY_UNAVAILABLE,
            "Home Assistant is not set up on this phone",
            "Tell the owner to add Home Assistant's address and an access token in PocketPilot's Automations settings",
        )
    }
    return try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: ToolException) {
        throw e
    } catch (e: Exception) {
        throw ToolException(
            ToolErrorCode.INTERNAL_ERROR,
            e.message ?: "Home Assistant could not be reached",
            "Check the entity id with home.entities; if Home Assistant is unreachable, try again later",
        )
    }
}

private fun HomeEntity.line(): String = if (name == id) "$id: $state" else "$id ($name): $state"

/** `home.entities`: finds Home Assistant entities by name, id or domain, with their state. */
class HomeEntitiesTool(
    private val home: HomeControl,
) : ToolHandler {
    override val spec: ToolSpec =
        ToolSpec(
            name = "home.entities",
            title = "Find Home Assistant devices",
            description =
                "Lists the owner's Home Assistant entities (lights, switches, locks, sensors, climate and more) as " +
                    "\"id (name): state\". Filter by words in the name or id, such as \"living room light\", and by domain, " +
                    "such as light. Use it to find the exact entity id before home.state or home.control.",
            inputSchema =
                schema(
                    """{"type":"object","properties":{
                    "query":{"type":"string","description":"Words that must all appear in the name or id, ignoring case"},
                    "domain":{"type":"string","description":"Only this domain, such as light, switch, lock or climate"},
                    "limit":{"type":"integer","minimum":1,"maximum":$MAX_LIMIT}},"additionalProperties":false}""",
                ),
            riskTier = RiskTier.READ,
            requiredScopes = setOf(Scope.HOME_READ),
            requiredCapabilities = setOf(CapabilityId.HOME_ASSISTANT),
            annotations = ToolAnnotations(readOnly = true, idempotent = true),
            availability = Availability(Phase.P1),
        )

    override suspend fun execute(
        call: ToolCall,
        session: Session,
    ): ToolResult {
        val words =
            call.arguments
                .string("query")
                .orEmpty()
                .lowercase()
                .split(' ', '_', '.')
                .filter(String::isNotBlank)
        val domain = call.arguments.string("domain")?.trim()?.lowercase()?.takeIf(String::isNotEmpty)
        val limit = (call.arguments.int("limit") ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val matches =
            home.reach { home.entities() }
                .filter { domain == null || it.domain == domain }
                .filter { entity ->
                    val text = "${entity.id} ${entity.name}".lowercase().replace('_', ' ')
                    words.all { it in text }
                }.sortedBy { it.id }
        if (matches.isEmpty()) return ToolResult.success("No entities match. Try fewer words, or leave out domain.")
        val more = if (matches.size > limit) "\n…and ${matches.size - limit} more; narrow the query." else ""
        return ToolResult.success(matches.take(limit).joinToString("\n") { it.line() } + more)
    }

    private companion object {
        const val DEFAULT_LIMIT = 30
        const val MAX_LIMIT = 100
    }
}

/** `home.state`: one entity's state and attributes. */
class HomeStateTool(
    private val home: HomeControl,
) : ToolHandler {
    override val spec: ToolSpec =
        ToolSpec(
            name = "home.state",
            title = "Read a Home Assistant device",
            description =
                "Reads one Home Assistant entity's state and attributes, such as a light's brightness or a " +
                    "thermostat's target temperature. Find the id with home.entities.",
            inputSchema =
                schema(
                    """{"type":"object","properties":{"entity":{"type":"string","description":"Entity id, such as light.kitchen"}},
                    "required":["entity"],"additionalProperties":false}""",
                ),
            riskTier = RiskTier.READ,
            requiredScopes = setOf(Scope.HOME_READ),
            requiredCapabilities = setOf(CapabilityId.HOME_ASSISTANT),
            annotations = ToolAnnotations(readOnly = true, idempotent = true),
            availability = Availability(Phase.P1),
        )

    override suspend fun execute(
        call: ToolCall,
        session: Session,
    ): ToolResult {
        val id = call.arguments.entityId()
        val entity = home.reach { home.entity(id) } ?: notFound(id)
        val attributes =
            entity.attributes.entries.joinToString("\n") { (key, value) -> "$key: $value" }.take(MAX_ATTRIBUTES)
        return ToolResult.success("${entity.line()}\n$attributes".trimEnd())
    }

    private companion object {
        const val MAX_ATTRIBUTES = 4_000
    }
}

/**
 * `home.control` and `home.control_secure`: call a Home Assistant action on an entity. The two differ only in which
 * actions they take: [secure] is confirmed by the owner on every call and is the only one that may unlock, disarm or
 * open a garage door, gate or door, or run a script or automation ([HomeSafety]).
 */
open class HomeControlTool(
    private val home: HomeControl,
    private val secure: Boolean = false,
) : ToolHandler {
    override val spec: ToolSpec =
        ToolSpec(
            name = if (secure) "home.control_secure" else "home.control",
            title = if (secure) "Control a lock, alarm or garage door" else "Control a Home Assistant device",
            description =
                if (secure) {
                    "Calls a Home Assistant action on a lock, alarm panel, garage door, gate or door, valve, script or " +
                        "automation, such as lock.unlock. The owner confirms every call. For lights, switches and " +
                        "other devices use home.control instead."
                } else {
                    "Calls a Home Assistant action on an entity, such as light.turn_on, switch.toggle, " +
                        "climate.set_temperature or media_player.volume_set. Extra fields go in data, such as " +
                        "{\"brightness_pct\": 40}. Find the entity with home.entities. Locks, alarms, garage doors, " +
                        "gates, doors, valves, scripts and automations are refused here: use home.control_secure."
                },
            inputSchema =
                schema(
                    """{"type":"object","properties":{
                    "service":{"type":"string","description":"domain.action, such as light.turn_on"},
                    "entity":{"type":"string","description":"Entity id, such as light.living_room"},
                    "data":{"type":"object","description":"Extra fields for the action, such as {\"brightness_pct\": 40}"}},
                    "required":["service","entity"],"additionalProperties":false}""",
                ),
            riskTier = if (secure) RiskTier.DESTRUCTIVE else RiskTier.INTERACT,
            requiredScopes = setOf(Scope.HOME_CONTROL),
            requiredCapabilities = setOf(CapabilityId.HOME_ASSISTANT),
            annotations = ToolAnnotations(destructive = secure),
            availability = Availability(Phase.P1),
        )

    override suspend fun confirmationDetail(call: ToolCall): String {
        val service = call.arguments.string("service").orEmpty()
        val id = call.arguments.string("entity").orEmpty()
        val name = runCatching { home.entity(id)?.name }.getOrNull()?.takeIf { it != id }
        val data = (call.arguments["data"] as? JsonObject)?.takeIf { it.isNotEmpty() }?.let { " with $it" }.orEmpty()
        return "$service on ${name?.let { "$it ($id)" } ?: id}$data"
    }

    override suspend fun execute(
        call: ToolCall,
        session: Session,
    ): ToolResult {
        val service = call.arguments.string("service")?.trim().orEmpty()
        if (!SERVICE.matches(service)) invalid("Give service as domain.action, such as light.turn_on")
        val id = call.arguments.entityId()
        val data = call.arguments["data"]?.let { it as? JsonObject ?: invalid("data must be an object") } ?: JsonObject(emptyMap())
        val entity = home.reach { home.entity(id) } ?: notFound(id)
        if (!secure && HomeSafety.needsConfirmation(service, entity)) {
            throw ToolException(
                ToolErrorCode.DENIED_BY_POLICY,
                "$service on ${entity.id} needs the owner's confirmation",
                "Call home.control_secure with the same arguments; the owner will be asked",
            )
        }
        home.reach { home.call(service, entity.id, data.toString()) }
        val after = runCatching { home.entity(entity.id) }.getOrNull()
        return ToolResult.success("Called $service on ${entity.id}. Now: ${(after ?: entity).state}")
    }

    private companion object {
        val SERVICE = Regex("^[A-Za-z0-9_]+\\.[A-Za-z0-9_]+$")
    }
}

/** `home.control_secure`, registered as its own tool so policy confirms it on every call. */
class HomeControlSecureTool(
    home: HomeControl,
) : HomeControlTool(home, secure = true)

private fun JsonObject.entityId(): String {
    val id = string("entity")?.trim().orEmpty()
    if (!ENTITY_ID.matches(id)) invalid("Give entity as domain.object_id, such as light.kitchen; find it with home.entities")
    return id.lowercase()
}

private fun notFound(id: String): Nothing =
    throw ToolException(ToolErrorCode.ELEMENT_NOT_FOUND, "Home Assistant has no entity $id", "Find the id with home.entities")

private val ENTITY_ID = Regex("^[A-Za-z0-9_]+\\.[A-Za-z0-9_]+$")
