package app.pocketpilot.core.tools

import app.pocketpilot.capability.api.home.HomeControl
import app.pocketpilot.capability.api.home.HomeEntity
import app.pocketpilot.core.audit.InMemoryAuditSink
import app.pocketpilot.core.model.PolicyProfile
import app.pocketpilot.core.model.ToolErrorCode
import app.pocketpilot.core.orchestrator.CallDispatcher
import app.pocketpilot.core.orchestrator.SessionFactory
import app.pocketpilot.core.orchestrator.ToolRegistry
import app.pocketpilot.core.policy.AppPolicy
import app.pocketpilot.core.policy.ConfirmationAnswer
import app.pocketpilot.core.policy.ConfirmationRequest
import app.pocketpilot.core.policy.Confirmer
import app.pocketpilot.core.policy.PolicyEngine
import app.pocketpilot.core.policy.RateLimiter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeToolsTest {
    private class FakeHome : HomeControl {
        override val backendId = "fake"
        override val available = MutableStateFlow(true)
        val entities =
            mutableMapOf(
                "light.living_room" to HomeEntity("light.living_room", "Living Room Lights", "off"),
                "light.kitchen" to HomeEntity("light.kitchen", "Kitchen", "on"),
                "switch.fan" to HomeEntity("switch.fan", "Fan", "off"),
                "lock.front_door" to HomeEntity("lock.front_door", "Front Door", "locked"),
                "cover.garage" to HomeEntity("cover.garage", "Garage", "closed", mapOf("device_class" to "\"garage\"")),
                "light.bedroom_kalebs_light" to HomeEntity("light.bedroom_kalebs_light", "Kaleb’s Light", "off"),
            )
        val calls = mutableListOf<Triple<String, String, String>>()

        /** How many reads a call's new state takes to show, like a plug that reports a moment late. */
        var reportAfterReads = 0
        private var pending: Pair<String, String>? = null
        private var readsLeft = 0

        override suspend fun entities() = entities.values.toList()

        override suspend fun entity(id: String): HomeEntity? {
            pending?.takeIf { it.first == id }?.let { (entity, state) ->
                if (readsLeft-- <= 0) {
                    entities[entity] = entities.getValue(entity).copy(state = state)
                    pending = null
                }
            }
            return entities[id]
        }

        override suspend fun call(
            service: String,
            entity: String,
            dataJson: String,
        ): String {
            calls += Triple(service, entity, dataJson)
            val now =
                when (service.substringAfter('.')) {
                    "turn_on" -> "on"
                    "unlock" -> "unlocked"
                    else -> entities.getValue(entity).state
                }
            pending = entity to now
            readsLeft = reportAfterReads
            return "[]"
        }
    }

    private val home = FakeHome()
    private val confirmations = mutableListOf<ConfirmationRequest>()
    private val dispatcher =
        CallDispatcher(
            ToolRegistry(
                setOf(HomeEntitiesTool(home), HomeStateTool(home), HomeControlTool(home), HomeControlSecureTool(home)),
                PolicyProfile.OSS,
            ),
            InMemoryAuditSink(),
            PolicyEngine(
                AppPolicy(),
                { null },
                Confirmer {
                    confirmations += it
                    ConfirmationAnswer.APPROVED
                },
                RateLimiter(Int.MAX_VALUE),
            ),
        )
    private val session = SessionFactory().localTokenSession()

    @Test
    fun `finds entities by words in their name and by domain`() =
        runTest {
            val result = dispatcher.dispatch(session, "home.entities", buildJsonObject { put("query", "living room") })
            assertFalse(result.isError)
            val text = result.content.toString()
            assertTrue("light.living_room (Living Room Lights): off" in text)
            assertFalse("kitchen" in text)
            val locks = dispatcher.dispatch(session, "home.entities", buildJsonObject { put("domain", "lock") }).content.toString()
            assertTrue("lock.front_door" in locks && "light." !in locks)
        }

    @Test
    fun `turns on a light without asking`() =
        runTest {
            val result =
                dispatcher.dispatch(
                    session,
                    "home.control",
                    buildJsonObject {
                        put("service", "light.turn_on")
                        put("entity", "light.living_room")
                        putJsonObject("data") { put("brightness_pct", 40) }
                    },
                )
            assertFalse(result.isError)
            assertTrue("Now: on" in result.content.toString())
            assertEquals(Triple("light.turn_on", "light.living_room", """{"brightness_pct":40}"""), home.calls.single())
            assertTrue(confirmations.isEmpty())
        }

    @Test
    fun `data cannot widen the action to an area or another entity`() =
        runTest {
            listOf("area_id", "device_id", "label_id", "floor_id", "entity_id", "target").forEach { key ->
                val result =
                    dispatcher.dispatch(
                        session,
                        "home.control",
                        buildJsonObject {
                            put("service", "light.turn_on")
                            put("entity", "light.living_room")
                            putJsonObject("data") { put(key, "garage") }
                        },
                    )
                assertTrue(result.isError, key)
            }
            assertTrue(home.calls.isEmpty())
        }

    @Test
    fun `refuses a lock or garage door and points to the secure tool`() =
        runTest {
            listOf("lock.unlock" to "lock.front_door", "cover.open_cover" to "cover.garage", "homeassistant.toggle" to "lock.front_door")
                .forEach { (service, entity) ->
                    val result =
                        dispatcher.dispatch(
                            session,
                            "home.control",
                            buildJsonObject {
                                put("service", service)
                                put("entity", entity)
                            },
                        )
                    assertTrue(result.isError)
                    assertEquals(ToolErrorCode.DENIED_BY_POLICY, result.errorCode)
                    assertTrue(result.recoveryHint.orEmpty().contains("home.control_secure"))
                }
            assertTrue(home.calls.isEmpty())
        }

    @Test
    fun `the secure tool asks the owner on every call, naming the device`() =
        runTest {
            repeat(2) {
                val result =
                    dispatcher.dispatch(
                        session,
                        "home.control_secure",
                        buildJsonObject {
                            put("service", "lock.unlock")
                            put("entity", "lock.front_door")
                        },
                    )
                assertFalse(result.isError)
            }
            assertEquals(2, confirmations.size)
            assertEquals("lock.unlock on Front Door (lock.front_door)", confirmations.first().detail)
            assertEquals(2, home.calls.size)
        }

    @Test
    fun `an unknown entity says how to find the right one`() =
        runTest {
            val result =
                dispatcher.dispatch(
                    session,
                    "home.control",
                    buildJsonObject {
                        put("service", "light.turn_on")
                        put("entity", "light.attic")
                    },
                )
            assertEquals(ToolErrorCode.ELEMENT_NOT_FOUND, result.errorCode)
            assertTrue(result.recoveryHint.orEmpty().contains("home.entities"))
        }

    @Test
    fun `reads one entity with its attributes`() =
        runTest {
            val result = dispatcher.dispatch(session, "home.state", buildJsonObject { put("entity", "cover.garage") })
            val text = result.content.toString()
            assertTrue("cover.garage (Garage): closed" in text && "device_class: \"garage\"" in text)
        }

    @Test
    fun `before Home Assistant is set up every call says how to set it up`() =
        runTest {
            home.available.value = false
            val result = dispatcher.dispatch(session, "home.entities", buildJsonObject {})
            assertEquals(ToolErrorCode.CAPABILITY_UNAVAILABLE, result.errorCode)
            assertTrue(result.recoveryHint.orEmpty().contains("Automations settings"))
        }

    @Test
    fun `finds a name with a curly apostrophe from a query with a straight one and filler words`() =
        runTest {
            val result =
                dispatcher.dispatch(
                    session,
                    "home.entities",
                    buildJsonObject {
                        put("domain", "light")
                        put("query", "Kaleb's light in the bedroom")
                    },
                )
            val text = result.content.toString()
            assertTrue("light.bedroom_kalebs_light (Kaleb’s Light): off" in text, text)
            assertFalse("living_room" in text)
        }

    @Test
    fun `waits for a device that reports its new state late`() =
        runTest {
            home.reportAfterReads = 3
            val result =
                dispatcher.dispatch(
                    session,
                    "home.control",
                    buildJsonObject {
                        put("service", "light.turn_on")
                        put("entity", "light.bedroom_kalebs_light")
                    },
                )
            assertTrue("Now: on" in result.content.toString(), result.content.toString())
        }

    @Test
    fun `says so when the state has not changed yet`() =
        runTest {
            val result =
                dispatcher.dispatch(
                    session,
                    "home.control",
                    buildJsonObject {
                        put("service", "switch.toggle")
                        put("entity", "switch.fan")
                    },
                )
            assertTrue("Now: off (unchanged so far" in result.content.toString(), result.content.toString())
        }
}
