package app.pocketpilot.agent.runtime

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TranscriptArgsTest {
    @Test
    fun `typed text is summarised and the other arguments are kept`() {
        val kept = transcriptArgs("""{"command":"ls","text":"hunter2 secret"}""")
        assertFalse(kept.contains("hunter2"))
        assertTrue(kept.contains("\"command\":\"ls\""))
    }

    @Test
    fun `arguments that do not parse are summarised by length alone`() {
        val kept = transcriptArgs("not json, password=abc")
        assertFalse(kept.contains("password"))
        assertTrue(kept.contains("\"length\":"))
    }
}
