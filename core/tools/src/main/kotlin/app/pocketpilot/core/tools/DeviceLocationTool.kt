package app.pocketpilot.core.tools

import app.pocketpilot.capability.api.LocationReader
import app.pocketpilot.core.model.Availability
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
import java.util.Locale

/**
 * `device.location`: where the phone is, with the nearest street address. Location is personal, so the tool has its
 * own scope and asks the owner on first use in a session.
 */
class DeviceLocationTool(
    private val reader: LocationReader,
) : ToolHandler {
    override val spec: ToolSpec =
        ToolSpec(
            name = "device.location",
            title = "Phone location",
            description =
                "Returns where the phone is: latitude and longitude, how accurate and how old the fix is, and the nearest " +
                    "street address when the phone can look it up. Takes no arguments.",
            inputSchema = schema("""{"type":"object","properties":{},"additionalProperties":false}"""),
            riskTier = RiskTier.SENSITIVE,
            requiredScopes = setOf(Scope.LOCATION_READ),
            annotations = ToolAnnotations(readOnly = true),
            availability = Availability(Phase.P1),
        )

    override suspend fun confirmationDetail(call: ToolCall): String = "Read where the phone is"

    override suspend fun execute(
        call: ToolCall,
        session: Session,
    ): ToolResult {
        if (!reader.permitted) {
            throw ToolException(
                ToolErrorCode.CAPABILITY_UNAVAILABLE,
                "PocketPilot is not allowed to read the location",
                "Tell the owner to allow location for PocketPilot in Android's app settings",
            )
        }
        val fix =
            reader.read() ?: throw ToolException(
                ToolErrorCode.CAPABILITY_UNAVAILABLE,
                "The phone has no location right now",
                "Location may be turned off; settings.set with location on can turn it on, then try again",
            )
        return ToolResult.success(describe(fix.latitude, fix.longitude, fix.accuracyMeters, fix.ageMillis, fix.address))
    }

    internal companion object {
        private const val MS_PER_MINUTE = 60_000L

        /** "51.04473, -114.07188 (within 12 m, 3 min ago). Address: 123 Main St, Calgary, AB". */
        fun describe(
            latitude: Double,
            longitude: Double,
            accuracyMeters: Float?,
            ageMillis: Long,
            address: String?,
        ): String {
            val where = String.format(Locale.ROOT, "%.5f, %.5f", latitude, longitude)
            val within = accuracyMeters?.let { "within ${it.toInt()} m, " }.orEmpty()
            val minutes = ageMillis / MS_PER_MINUTE
            val age = if (minutes < 1) "just now" else "$minutes min ago"
            return "$where ($within$age)." + address?.let { " Address: $it" }.orEmpty()
        }
    }
}
