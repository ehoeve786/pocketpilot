package app.pocketpilot.core.tools

import app.pocketpilot.capability.api.termux.TermuxCommands
import app.pocketpilot.capability.api.termux.TermuxException
import app.pocketpilot.capability.api.termux.TermuxOutput
import app.pocketpilot.core.audit.InMemoryAuditSink
import app.pocketpilot.core.model.ContentPart
import app.pocketpilot.core.model.PolicyProfile
import app.pocketpilot.core.model.Scope
import app.pocketpilot.core.model.ToolErrorCode
import app.pocketpilot.core.model.ToolResult
import app.pocketpilot.core.orchestrator.CallDispatcher
import app.pocketpilot.core.orchestrator.SessionFactory
import app.pocketpilot.core.orchestrator.ToolRegistry
import app.pocketpilot.core.policy.AppPolicy
import app.pocketpilot.core.policy.ConfirmationAnswer
import app.pocketpilot.core.policy.ConfirmationRequest
import app.pocketpilot.core.policy.PolicyEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TermuxRunToolTest {
    private val ran = mutableListOf<Pair<String, Boolean>>()
    private var refuse: String? = null
    private var on = true

    private val termux =
        object : TermuxCommands {
            override val backendId = "termux"
            override val available = MutableStateFlow(true)

            override suspend fun run(
                command: String,
                wait: Boolean,
                timeoutMs: Long,
            ): TermuxOutput? {
                refuse?.let { throw TermuxException(it) }
                ran += command to wait
                return if (wait) TermuxOutput("merged 4 buses\n", "", if (command.startsWith("false")) 1 else 0) else null
            }
        }

    private val asked = mutableListOf<ConfirmationRequest>()
    private val policy =
        PolicyEngine(appPolicy = AppPolicy(), foreground = { null }, confirmer = { request ->
            asked += request
            ConfirmationAnswer.APPROVED
        })
    private val dispatcher =
        CallDispatcher(ToolRegistry(setOf(TermuxRunTool(termux) { on }), PolicyProfile.OSS), InMemoryAuditSink(), policy)
    private val session = SessionFactory().clientSession("Claude", Scope.KNOWN)

    private fun call(
        command: String,
        wait: Boolean? = null,
    ) = buildJsonObject {
        put("command", command)
        wait?.let { put("wait", it) }
    }

    private fun ToolResult.text() = content.filterIsInstance<ContentPart.Text>().joinToString("\n") { it.text }

    @Test
    fun `runs a command once approved and returns its output`() =
        runTest {
            val result = dispatcher.dispatch(session, "termux.run", call("python merge.py"))
            assertEquals("Exit code 0\nstdout:\nmerged 4 buses", result.text())
            assertEquals("python merge.py", asked.single().detail)
            dispatcher.dispatch(session, "termux.run", call("python merge.py"))
            assertEquals(1, asked.size, "the same command runs again without asking")
            assertTrue(dispatcher.dispatch(session, "termux.run", call("false")).isError)
        }

    @Test
    fun `a background start returns at once`() =
        runTest {
            val result = dispatcher.dispatch(session, "termux.run", call("python merge.py > ~/merge.log 2>&1", wait = false))
            assertTrue(result.text().startsWith("Started in Termux"))
            assertEquals(listOf("python merge.py > ~/merge.log 2>&1" to false), ran)
        }

    @Test
    fun `Termux's refusal and the shell switch come back as errors with a hint`() =
        runTest {
            refuse = "Termux refused the command: allow-external-apps is not true"
            val refused = dispatcher.dispatch(session, "termux.run", call("ls"))
            assertEquals(ToolErrorCode.CAPABILITY_UNAVAILABLE, refused.errorCode)
            assertTrue(refused.text().contains("allow-external-apps"))

            on = false
            assertEquals(ToolErrorCode.CAPABILITY_UNAVAILABLE, dispatcher.dispatch(session, "termux.run", call("ls")).errorCode)
        }
}
