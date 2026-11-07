package app.pocketpilot.agent.runtime

import app.pocketpilot.agent.providers.api.ModelErrorCode
import app.pocketpilot.agent.providers.api.ModelEvent
import app.pocketpilot.agent.providers.api.ModelInfo
import app.pocketpilot.agent.providers.api.ModelProvider
import app.pocketpilot.agent.providers.api.ModelRequest
import app.pocketpilot.agent.providers.api.StopReason
import app.pocketpilot.core.common.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class KeyRotatingProviderTest {
    private class Key(
        val name: String,
        var limited: Boolean,
    ) : ModelProvider {
        var calls = 0
        override val id = "gemini"
        override val displayName = "Gemini"

        override suspend fun listModels() = listOf(ModelInfo(name))

        override fun stream(request: ModelRequest): Flow<ModelEvent> {
            calls++
            return if (limited) {
                flowOf(ModelEvent.Error(ModelErrorCode.RATE_LIMIT, "429 from $name", retryAfterMs = 1_000))
            } else {
                flowOf(ModelEvent.TextDelta(name), ModelEvent.Stop(StopReason.END_TURN))
            }
        }
    }

    /** Fails every request with [code]. */
    private class Failing(
        override val id: String,
        private val code: ModelErrorCode,
    ) : ModelProvider {
        var calls = 0
        override val displayName = id

        override suspend fun listModels() = emptyList<ModelInfo>()

        override fun stream(request: ModelRequest): Flow<ModelEvent> {
            calls++
            return flowOf(ModelEvent.Error(code, "refused"))
        }
    }

    private val request = ModelRequest(model = "m", system = "s", messages = emptyList())

    /** The events a run uses for its reply; the notes for the run log are checked on their own. */
    private suspend fun Flow<ModelEvent>.replies() = toList().filterNot { it is ModelEvent.Notice }

    @Test
    fun rateLimitedKeyHandsOverToTheNextAndStaysThere() =
        runTest {
            val a = Key("a", limited = true)
            val b = Key("b", limited = false)
            val provider = KeyRotatingProvider(listOf(a, b))

            val first = provider.stream(request).replies()
            assertEquals(listOf(ModelEvent.TextDelta("b"), ModelEvent.Stop(StopReason.END_TURN)), first)

            provider.stream(request).replies()
            assertEquals(1, a.calls)
            assertEquals(2, b.calls)
        }

    @Test
    fun whenEveryKeyIsLimitedTheErrorIsPassedOn() =
        runTest {
            val provider = KeyRotatingProvider(listOf(Key("a", true), Key("b", true)))

            val events = provider.stream(request).replies()

            assertEquals(1, events.size)
            assertEquals(ModelErrorCode.RATE_LIMIT, (events.single() as ModelEvent.Error).code)
        }

    @Test
    fun otherErrorsAreNotRetriedOnAnotherKey() =
        runTest {
            val broken =
                object : ModelProvider {
                    override val id = "gemini"
                    override val displayName = "Gemini"

                    override suspend fun listModels() = emptyList<ModelInfo>()

                    override fun stream(request: ModelRequest): Flow<ModelEvent> =
                        flowOf(ModelEvent.Error(ModelErrorCode.INVALID_REQUEST, "bad"))
                }
            val spare = Key("b", false)

            val events = KeyRotatingProvider(listOf(broken, spare)).stream(request).replies()

            assertEquals(ModelErrorCode.INVALID_REQUEST, (events.single() as ModelEvent.Error).code)
            assertEquals(0, spare.calls)
        }

    @Test
    fun roundRobinStartsEachRequestOnTheNextKey() =
        runTest {
            val a = Key("a", limited = false)
            val b = Key("b", limited = false)
            val provider = KeyRotatingProvider(listOf(a, b), roundRobin = true)

            val served = (1..4).map { (provider.stream(request).replies().first() as ModelEvent.TextDelta).text }

            assertEquals(listOf("a", "b", "a", "b"), served)
        }

    @Test
    fun roundRobinSkipsALimitedKeyWhileItCoolsDown() =
        runTest {
            val a = Key("a", limited = true)
            val b = Key("b", limited = false)
            val provider = KeyRotatingProvider(listOf(a, b), roundRobin = true, clock = Clock { 0L })

            val served = (1..3).map { (provider.stream(request).replies().first() as ModelEvent.TextDelta).text }

            assertEquals(listOf("b", "b", "b"), served)
            assertEquals(1, a.calls, "the limited key sits out its cooldown, which the server's Retry-After sets")
        }

    @Test
    fun aLimitedKeyIsTriedAgainOnceItsCooldownPasses() =
        runTest {
            var now = 0L
            val a = Key("a", limited = true)
            val b = Key("b", limited = false)
            val provider = KeyRotatingProvider(listOf(a, b), roundRobin = true, clock = Clock { now })
            provider.stream(request).replies()

            a.limited = false
            now = 1_000
            provider.stream(request).replies()
            val third = provider.stream(request).replies().first() as ModelEvent.TextDelta

            assertEquals("a", third.text)
            assertEquals(2, a.calls)
        }

    @Test
    fun aRefusedKeyIsNotTriedAgainForTheLifeOfTheProvider() =
        runTest {
            var now = 0L
            val refused = Failing("gemini", ModelErrorCode.AUTH)
            val b = Key("b", limited = false)
            val provider = KeyRotatingProvider(listOf(refused, b), roundRobin = true, clock = Clock { now })
            provider.stream(request).replies()

            now += 30L * 24 * 60 * 60_000
            repeat(4) { provider.stream(request).replies() }

            assertEquals(1, refused.calls)
        }

    @Test
    fun whenEveryKeyIsCoolingTheRequestStillGoesOut() =
        runTest {
            val a = Key("a", limited = true)
            val b = Key("b", limited = true)
            val provider = KeyRotatingProvider(listOf(a, b), clock = Clock { 0L })
            provider.stream(request).replies()

            val events = provider.stream(request).replies()

            assertEquals(2, a.calls)
            assertEquals(2, b.calls)
            assertEquals(ModelErrorCode.RATE_LIMIT, (events.single() as ModelEvent.Error).code)
        }

    @Test
    fun aTimeoutIsLeftToTheNextProviderInsteadOfTheNextKey() =
        runTest {
            val slow =
                object : ModelProvider {
                    override val id = "gemini"
                    override val displayName = "Gemini"

                    override suspend fun listModels() = emptyList<ModelInfo>()

                    override fun stream(request: ModelRequest): Flow<ModelEvent> =
                        flowOf(ModelEvent.Error(ModelErrorCode.NETWORK, "timeout"))
                }
            val spare = Key("b", false)

            val events = KeyRotatingProvider(listOf(slow, spare), roundRobin = true).stream(request).replies()

            assertEquals(ModelErrorCode.NETWORK, (events.single() as ModelEvent.Error).code)
            assertEquals(0, spare.calls)
        }

    @Test
    fun aSkippedKeyLeavesANoteAndAFailureOfEveryKeySaysSo() =
        runTest {
            val events = KeyRotatingProvider(listOf(Key("a", true), Key("b", false))).stream(request).toList()
            assertEquals(ModelEvent.Notice("Key 1 hit its rate limit; trying key 2"), events.first())
            assertEquals(ModelEvent.TextDelta("b"), events[1])

            val failed = KeyRotatingProvider(listOf(Key("a", true), Key("b", true))).stream(request).toList()
            assertEquals(ModelEvent.Notice("Key 1 hit its rate limit; trying key 2"), failed.first())
            assertEquals("All 2 keys failed. 429 from b", (failed.last() as ModelEvent.Error).message)
        }

    @Test
    fun theNoteNamesTheLimitWhenTheAdapterDoes() =
        runTest {
            val spent =
                object : ModelProvider {
                    override val id = "gemini"
                    override val displayName = "Gemini"

                    override suspend fun listModels() = emptyList<ModelInfo>()

                    override fun stream(request: ModelRequest): Flow<ModelEvent> =
                        flowOf(
                            ModelEvent.Error(
                                ModelErrorCode.QUOTA,
                                "Gemini daily request limit (free tier) reached. HTTP 429: {}",
                            ),
                        )
                }
            val events = KeyRotatingProvider(listOf(spent, Key("b", false))).stream(request).toList()
            assertEquals(
                ModelEvent.Notice("Key 1 is out of quota: Gemini daily request limit (free tier) reached; trying key 2"),
                events.first(),
            )
        }
}
