package app.pocketpilot.core.automation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuleStoreUpsertTest {
    private class MemoryPersistence : Persistence {
        private var text: String? = null

        override fun load(): String? = text

        override fun save(text: String) {
            this.text = text
        }
    }

    @Test
    fun `saving the builder's copy keeps the last run and the pin the rule got meanwhile`() {
        val store = RuleStore(MemoryPersistence())
        val opened = Rule("r1", "Old", triggers = listOf(TriggerSpec("manual")))
        store.upsert(opened)
        store.addRun(RunRecord("run1", "r1", "Old", "Run button", 10, 20, RunOutcome.OK))
        store.setPinned("r1", true)

        store.upsert(opened.copy(name = "New"))

        val saved = store.rule("r1")!!
        assertEquals("New", saved.name)
        assertEquals(20, saved.lastRun?.atMillis)
        assertTrue(saved.pinned)
    }
}
