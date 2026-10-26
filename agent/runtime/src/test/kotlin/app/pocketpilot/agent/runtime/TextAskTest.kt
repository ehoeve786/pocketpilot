package app.pocketpilot.agent.runtime

import app.pocketpilot.agent.providers.api.ModelErrorCode
import app.pocketpilot.agent.providers.api.ModelEvent
import app.pocketpilot.agent.providers.api.ModelInfo
import app.pocketpilot.agent.providers.api.ModelProvider
import app.pocketpilot.agent.providers.api.ModelRequest
import app.pocketpilot.agent.providers.api.StopReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextAskTest {
    private class OneReply(
        private vararg val events: ModelEvent,
    ) : ModelProvider {
        val requests = mutableListOf<ModelRequest>()
        override val id = "fake"
        override val displayName = "Fake"

        override suspend fun listModels() = listOf(ModelInfo("fake-1"))

        override fun stream(request: ModelRequest): Flow<ModelEvent> {
            requests += request
            return flowOf(*events)
        }
    }

    private fun model(
        provider: ModelProvider,
        fallback: RunModel? = null,
    ) = RunModel(provider, "fake-1", estimateCost = { _, _ -> 0.001 }, fallback = fallback, label = provider.displayName)

    @Test
    fun `asks once with no tools and returns the trimmed answer`() =
        runTest {
            val provider =
                OneReply(
                    ModelEvent.TextDelta(" 22 East Cordova Street, "),
                    ModelEvent.TextDelta("Vancouver, BC\n"),
                    ModelEvent.Usage(100, 10),
                    ModelEvent.Stop(StopReason.END_TURN),
                )
            val answer = TextAsk.ask(model(provider), "Find the address in: Breka, 22 East Cordova Street")
            assertEquals("22 East Cordova Street, Vancouver, BC", answer.text)
            assertEquals(0.001, answer.costUsd)
            val request = provider.requests.single()
            assertTrue(request.tools.isEmpty())
            assertEquals(TextAsk.SYSTEM, request.system)
        }

    @Test
    fun `a failed model hands over to the next, and no answer says why`() =
        runTest {
            val broken = OneReply(ModelEvent.Error(ModelErrorCode.RATE_LIMIT, "slow down"))
            val backup = OneReply(ModelEvent.TextDelta("ok"), ModelEvent.Stop(StopReason.END_TURN))
            assertEquals("ok", TextAsk.ask(model(broken, fallback = model(backup)), "q").text)

            val empty = TextAsk.ask(model(OneReply(ModelEvent.Stop(StopReason.END_TURN))), "q")
            assertNull(empty.text)
            assertEquals("Fake gave no answer", empty.problem)
        }
}
