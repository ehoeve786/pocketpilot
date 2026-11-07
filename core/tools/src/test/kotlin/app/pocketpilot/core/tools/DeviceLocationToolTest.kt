package app.pocketpilot.core.tools

import app.pocketpilot.capability.api.DeviceLocation
import app.pocketpilot.capability.api.LocationReader
import app.pocketpilot.core.audit.InMemoryAuditSink
import app.pocketpilot.core.model.ContentPart
import app.pocketpilot.core.model.PolicyProfile
import app.pocketpilot.core.model.ToolErrorCode
import app.pocketpilot.core.orchestrator.CallDispatcher
import app.pocketpilot.core.orchestrator.SessionFactory
import app.pocketpilot.core.orchestrator.ToolRegistry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeviceLocationToolTest {
    private class FakeLocation(
        override var permitted: Boolean = true,
        var fix: DeviceLocation? = null,
    ) : LocationReader {
        override suspend fun read() = fix
    }

    private val reader = FakeLocation()
    private val dispatcher =
        CallDispatcher(ToolRegistry(setOf(DeviceLocationTool(reader)), PolicyProfile.OSS), InMemoryAuditSink(), allowingPolicy())
    private val session = SessionFactory().localTokenSession()

    @Test
    fun `gives the place with its accuracy, age and address`() =
        runTest {
            reader.fix = DeviceLocation(51.044733, -114.071883, 12.4f, 180_000, "123 Main St, Calgary, AB")
            val result = dispatcher.dispatch(session, "device.location", buildJsonObject {})
            assertEquals(
                "51.04473, -114.07188 (within 12 m, 3 min ago). Address: 123 Main St, Calgary, AB",
                (result.content.single() as ContentPart.Text).text,
            )
        }

    @Test
    fun `a fresh fix without an address says just now`() {
        assertEquals("1.00000, 2.00000 (just now).", DeviceLocationTool.describe(1.0, 2.0, null, 5_000, null))
    }

    @Test
    fun `says what to do without permission or without a fix`() =
        runTest {
            reader.permitted = false
            val refused = dispatcher.dispatch(session, "device.location", buildJsonObject {})
            assertEquals(ToolErrorCode.CAPABILITY_UNAVAILABLE, refused.errorCode)
            assertTrue(refused.recoveryHint.orEmpty().contains("allow location"))

            reader.permitted = true
            val none = dispatcher.dispatch(session, "device.location", buildJsonObject {})
            assertEquals(ToolErrorCode.CAPABILITY_UNAVAILABLE, none.errorCode)
            assertTrue(none.recoveryHint.orEmpty().contains("settings.set"))
        }
}
