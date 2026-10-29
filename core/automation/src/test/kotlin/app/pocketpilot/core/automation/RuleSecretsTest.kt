package app.pocketpilot.core.automation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuleSecretsTest {
    private class Vault : RuleSecrets {
        val values = mutableMapOf<String, String>()

        override fun get(name: String): String? = values[name]

        override fun put(
            name: String,
            value: String,
        ) {
            values[name] = value
        }

        override fun remove(name: String) {
            values.remove(name)
        }
    }

    private class Disk : Persistence {
        var text: String? = null

        override fun load(): String? = text

        override fun save(text: String) {
            this.text = text
        }
    }

    private fun rule(headers: String) =
        Rule(
            id = "r1",
            name = "Send",
            triggers = listOf(TriggerSpec("manual")),
            actions = listOf(ActionStep("http.request", mapOf("url" to "https://example.com", "headers" to headers))),
        )

    @Test
    fun `the rules file holds no credential, but the rule still has it`() {
        val disk = Disk()
        val store = RuleStore(disk, secrets = Vault())
        store.upsert(rule("Authorization: Bearer abc123"))
        assertFalse(disk.text!!.contains("abc123"))
        assertEquals(
            "Authorization: Bearer abc123",
            store
                .rule("r1")!!
                .actions
                .single()
                .params["headers"],
        )
    }

    @Test
    fun `a rule loaded from the file gets its credential back`() {
        val disk = Disk()
        val vault = Vault()
        RuleStore(disk, secrets = vault).upsert(rule("Authorization: Bearer abc123"))
        val reloaded = RuleStore(disk, secrets = vault)
        assertEquals(
            "Authorization: Bearer abc123",
            reloaded
                .rule("r1")!!
                .actions
                .single()
                .params["headers"],
        )
    }

    @Test
    fun `deleting a rule removes its credential from the vault`() {
        val disk = Disk()
        val vault = Vault()
        val store = RuleStore(disk, secrets = vault)
        store.upsert(rule("Authorization: Bearer abc123"))
        store.delete("r1")
        assertTrue(vault.values.isEmpty())
        assertNull(store.rule("r1"))
    }

    @Test
    fun `without a vault the value stays in the file, as a plain store keeps it`() {
        val disk = Disk()
        RuleStore(disk).upsert(rule("Authorization: Bearer abc123"))
        assertTrue(disk.text!!.contains("abc123"))
    }
}
